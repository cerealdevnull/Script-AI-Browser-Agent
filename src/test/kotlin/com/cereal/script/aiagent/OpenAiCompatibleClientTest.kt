package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge contract: the OpenAI-compatible Chat Completions adapter against a fake API server. */
class OpenAiCompatibleClientTest {
    private val api = MockWebServer().apply { start() }
    private val baseUrl = api.url("/v1").toString()

    @AfterTest
    fun tearDown() = api.close()

    // Gemini's OpenAI endpoint leaves thinking out of completion_tokens: total − prompt is the real output.
    private val toolCallResponse =
        """
        {"id":"c1","model":"gemini-3.5-flash-lite","choices":[{"index":0,"finish_reason":"tool_calls",
          "message":{"role":"assistant","content":null,"extra_content":{"google":{"thought_signature":"sig-1"}},
                     "tool_calls":[{"id":"call_1","type":"function","function":{"name":"click","arguments":"{\"ref\":3}"}}]}}],
         "usage":{"prompt_tokens":1200,"completion_tokens":20,"total_tokens":1300,"prompt_tokens_details":{"cached_tokens":900}}}
        """.trimIndent()

    private fun request(vararg messages: Message) = ModelRequest("system prompt", Agent.TOOLS, messages.toList())

    private fun enqueue(n: Int = 1) = repeat(n) { api.enqueue(MockResponse.Builder().body(toolCallResponse).build()) }

    @Test
    fun `parses tool calls, stop reason, model and usage with output as total minus prompt`() =
        runBlocking {
            enqueue()

            val turn = OpenAiCompatibleClient(baseUrl, "sk-test", "gemini-3.5-flash-lite").send(request(Message.User("Goal: x")))

            val call = turn.toolCalls.single()
            assertEquals("call_1", call.id)
            assertEquals("click", call.name)
            assertEquals("3", call.args["ref"]!!.jsonPrimitive.content)
            assertEquals("tool_calls", turn.stopReason)
            assertEquals("gemini-3.5-flash-lite", turn.model)
            // input excludes cached prompt tokens (as Anthropic's input_tokens does); output = 1300 − 1200
            assertEquals(listOf(300L, 100L, 900L, 0L), turn.usage.let { listOf(it.input, it.output, it.cacheRead, it.cacheWrite) })
        }

    @Test
    fun `sends the request shape and echoes the assistant message back unchanged`() =
        runBlocking {
            enqueue(2)
            val client = OpenAiCompatibleClient(baseUrl, "sk-test", "gemini-3.5-flash-lite")
            val first = Message.User("Goal: x")
            val turn = client.send(request(first))
            api.takeRequest()

            client.send(request(first, Message.Assistant(turn), Message.ToolResults(listOf(ToolResult("call_1", "bad ref", isError = true)))))

            val recorded = api.takeRequest()
            assertEquals("/v1/chat/completions", recorded.url.encodedPath)
            assertEquals("Bearer sk-test", recorded.headers["Authorization"])
            val bodyText = recorded.body!!.utf8()
            assertFalse("sk-test" in bodyText, "the API key goes only in the auth header")
            val body = Json.parseToJsonElement(bodyText).jsonObject
            assertEquals(
                Json.parseToJsonElement("""{"model":"gemini-3.5-flash-lite","tool_choice":"auto"}"""),
                JsonObject(body - "tools" - "messages"),
            )
            val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
            assertTrue(tools.all { it["type"]!!.jsonPrimitive.content == "function" })
            val functions = tools.map { it["function"]!!.jsonObject }
            assertEquals(Agent.TOOLS.map { it.name }, functions.map { it["name"]!!.jsonPrimitive.content })
            assertTrue(functions.all { it["strict"]!!.jsonPrimitive.content == "true" && it["parameters"] is JsonObject })
            assertEquals(
                Json.parseToJsonElement(
                    """
                    [{"role":"system","content":"system prompt"},
                     {"role":"user","content":"Goal: x"},
                     {"role":"assistant","content":null,"extra_content":{"google":{"thought_signature":"sig-1"}},
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"click","arguments":"{\"ref\":3}"}}]},
                     {"role":"tool","tool_call_id":"call_1","content":"ERROR: bad ref"}]
                    """,
                ),
                body["messages"],
            )
        }

    @Test
    fun `the API key is optional and then no auth header is sent`() =
        runBlocking {
            enqueue()

            OpenAiCompatibleClient(baseUrl, null, "llama3").send(request(Message.User("Goal: x")))

            assertNull(api.takeRequest().headers["Authorization"])
        }

    @Test
    fun `old snapshot results are trimmed, the last two are kept whole`() =
        runBlocking {
            enqueue(4)
            val client = OpenAiCompatibleClient(baseUrl, "sk-test", "gpt-5")
            val messages = mutableListOf<Message>(Message.User("Goal: x"))
            repeat(3) { i ->
                val turn = client.send(request(*messages.toTypedArray()))
                messages += Message.Assistant(turn)
                messages += Message.ToolResults(listOf(ToolResult("call_1", "ok: step $i\n<page_content>\nsnapshot $i</page_content>")))
            }
            repeat(3) { api.takeRequest() }

            client.send(request(*messages.toTypedArray()))

            val sent =
                Json.parseToJsonElement(api.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray
                    .map { it.jsonObject }.filter { it["role"]!!.jsonPrimitive.content == "tool" }
                    .map { it["content"]!!.jsonPrimitive.content }
            assertEquals(
                listOf(
                    "ok: step 0\n[older snapshot removed]",
                    "ok: step 1\n<page_content>\nsnapshot 1</page_content>",
                    "ok: step 2\n<page_content>\nsnapshot 2</page_content>",
                ),
                sent,
            )
        }

    @Test
    fun `a screenshot follows its tool message as a user image message`() =
        runBlocking {
            enqueue()

            OpenAiCompatibleClient(baseUrl, null, "gpt-5")
                .send(request(Message.User("Goal: x"), Message.ToolResults(listOf(ToolResult("call_1", "ok: screenshot", imageJpeg = "AAAA")))))

            val sent = Json.parseToJsonElement(api.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "tool", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
            val image = sent.last()["content"]!!.jsonArray.last().jsonObject
            assertEquals("data:image/jpeg;base64,AAAA", image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an invalid-model 400 fails at once with the rejected message`() {
        api.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"The model `gpt-9` does not exist","type":"invalid_request_error"}}""").build())

        val e =
            assertFailsWith<ModelException> {
                runBlocking { OpenAiCompatibleClient(baseUrl, "sk-test", "gpt-9").send(request(Message.User("Goal: x"))) }
            }

        assertEquals("Model/API key rejected: HTTP 400: The model `gpt-9` does not exist", e.message)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `a Gemini 429 waits the RetryInfo retryDelay`() =
        runBlocking {
            val sleeps = mutableListOf<Long>()
            api.enqueue(
                MockResponse.Builder().code(429).body(
                    """
                    [{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED",
                      "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"12.5s"}]}}]
                    """.trimIndent(),
                ).build(),
            )
            api.enqueue(MockResponse.Builder().body(toolCallResponse).build())

            OpenAiCompatibleClient(baseUrl, "key", "gemini-3-flash") { sleeps += it }.send(request(Message.User("Goal: x")))

            assertEquals(listOf(12_500L), sleeps)
        }

    @Test
    fun `a refusal maps to the refusal stop reason with its text`() =
        runBlocking {
            api.enqueue(
                MockResponse.Builder().body(
                    """
                    {"model":"gpt-5","choices":[{"index":0,"finish_reason":"stop",
                      "message":{"role":"assistant","content":null,"refusal":"I can't help with that."}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                    """.trimIndent(),
                ).build(),
            )

            val turn = OpenAiCompatibleClient(baseUrl, "sk-test", "gpt-5").send(request(Message.User("Goal: x")))

            assertEquals("refusal", turn.stopReason)
            assertEquals("I can't help with that.", turn.text)
        }
}
