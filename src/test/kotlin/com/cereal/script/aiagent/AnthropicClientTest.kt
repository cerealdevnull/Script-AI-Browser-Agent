package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertTrue

/** Edge contract: the Anthropic Messages adapter against a fake API server. */
class AnthropicClientTest {
    private val api = MockWebServer().apply { start() }
    private val sleeps = mutableListOf<Long>()
    private val client = AnthropicClient(api.url("/").toString().removeSuffix("/"), "sk-test", "claude-opus-5-5") { sleeps += it }

    @AfterTest
    fun tearDown() = api.close()

    private val toolUseResponse =
        """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"tool_use",
         "content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                    {"type":"tool_use","id":"toolu_1","name":"click","input":{"ref":3}}],
         "usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":900,"cache_creation_input_tokens":300}}
        """.trimIndent()

    private fun request(vararg messages: Message) = ModelRequest("system prompt", Agent.TOOLS, messages.toList())

    @Test
    fun `parses tool calls, stop reason, model and usage`() =
        runBlocking {
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())

            val turn = client.send(request(Message.User("Goal: x")))

            val call = turn.toolCalls.single()
            assertEquals("toolu_1", call.id)
            assertEquals("click", call.name)
            assertEquals(3, call.args["ref"]!!.jsonPrimitive.content.toInt())
            assertEquals("tool_use", turn.stopReason)
            assertEquals("claude-opus-5-5", turn.model)
            assertEquals(listOf(1200L, 80L, 900L, 300L), turn.usage.let { listOf(it.input, it.output, it.cacheRead, it.cacheWrite) })
        }

    @Test
    fun `sends the decided request shape and echoes the assistant turn back unchanged`() =
        runBlocking {
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())
            val first = Message.User("Goal: x")
            val turn = client.send(request(first))
            api.takeRequest()

            client.send(request(first, Message.Assistant(turn), Message.ToolResults(listOf(ToolResult("toolu_1", "bad ref", isError = true)))))

            val recorded = api.takeRequest()
            assertEquals("/v1/messages", recorded.url.encodedPath)
            assertEquals("sk-test", recorded.headers["x-api-key"])
            assertEquals("2023-06-01", recorded.headers["anthropic-version"])
            assertEquals("server-side-fallback-2026-07-01", recorded.headers["anthropic-beta"])
            val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {"model":"claude-opus-5-5","max_tokens":16000,"thinking":{"type":"adaptive"},
                     "output_config":{"effort":"medium"},"tool_choice":{"type":"auto"},
                     "cache_control":{"type":"ephemeral"},"fallbacks":"default","system":"system prompt"}
                    """,
                ),
                JsonObject(body - "tools" - "messages"),
            )
            val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(Agent.TOOLS.map { it.name }, tools.map { it["name"]!!.jsonPrimitive.content })
            assertTrue(tools.all { it["strict"]!!.jsonPrimitive.content == "true" && it["input_schema"] is JsonObject })
            assertEquals(
                Json.parseToJsonElement(
                    """
                    [{"role":"user","content":"Goal: x"},
                     {"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                                                    {"type":"tool_use","id":"toolu_1","name":"click","input":{"ref":3}}]},
                     {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"bad ref","is_error":true}]}]
                    """,
                ),
                body["messages"],
            )
        }

    @Test
    fun `history is append-only, old snapshots are not trimmed, and the API key stays out of the body`() =
        runBlocking {
            repeat(4) { api.enqueue(MockResponse.Builder().body(toolUseResponse).build()) }
            val messages = mutableListOf<Message>(Message.User("Goal: x"))
            repeat(3) { i ->
                messages += Message.Assistant(client.send(request(*messages.toTypedArray())))
                messages += Message.ToolResults(listOf(ToolResult("toolu_1", "ok: step $i\n<page_content>\nsnapshot $i</page_content>")))
            }
            repeat(3) { api.takeRequest() }

            client.send(request(*messages.toTypedArray()))

            val bodyText = api.takeRequest().body!!.utf8()
            assertFalse("sk-test" in bodyText, "the API key goes only in the auth header")
            val results =
                Json.parseToJsonElement(bodyText).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
                    .filter { it["role"]!!.jsonPrimitive.content == "user" }
                    .mapNotNull { (it["content"] as? JsonArray)?.single()?.jsonObject?.get("content")?.jsonPrimitive?.content }
            assertEquals((0..2).map { "ok: step $it\n<page_content>\nsnapshot $it</page_content>" }, results)
        }

    @Test
    fun `a screenshot goes back as an image block in the tool result`() =
        runBlocking {
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())

            client.send(request(Message.User("Goal: x"), Message.ToolResults(listOf(ToolResult("toolu_1", "ok: screenshot", imageJpeg = "AAAA")))))

            val result = Json.parseToJsonElement(api.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray.last()
                .jsonObject["content"]!!.jsonArray.single().jsonObject
            assertEquals(
                Json.parseToJsonElement(
                    """[{"type":"text","text":"ok: screenshot"},{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"AAAA"}}]""",
                ),
                result["content"],
            )
        }

    @Test
    fun `an auth error fails at once with the rejected message`() {
        api.enqueue(
            MockResponse.Builder().code(401)
                .body("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").build(),
        )

        val e = assertFailsWith<ModelException> { runBlocking { client.send(request(Message.User("Goal: x"))) } }

        assertEquals("Model/API key rejected: HTTP 401: invalid x-api-key", e.message)
        assertFalse(e.transient)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `a 429 waits the retry-after delay and retries`() =
        runBlocking {
            api.enqueue(MockResponse.Builder().code(429).addHeader("retry-after", "7").body("""{"error":{"message":"rate limited"}}""").build())
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())

            val turn = client.send(request(Message.User("Goal: x")))

            assertEquals("click", turn.toolCalls.single().name)
            assertEquals(listOf(7_000L), sleeps)
        }

    @Test
    fun `the 4th consecutive 429 fails the call as transient`() {
        repeat(4) { api.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"rate limited"}}""").build()) }

        val e = assertFailsWith<ModelException> { runBlocking { client.send(request(Message.User("Goal: x"))) } }

        assertEquals("Model error: HTTP 429: rate limited", e.message)
        assertTrue(e.transient)
        assertEquals(4, api.requestCount)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
    }

    @Test
    fun `5xx and overloaded responses are retried with exponential backoff`() =
        runBlocking {
            api.enqueue(MockResponse.Builder().code(500).body("""{"error":{"message":"internal"}}""").build())
            api.enqueue(MockResponse.Builder().code(529).body("""{"error":{"type":"overloaded_error","message":"Overloaded"}}""").build())
            api.enqueue(MockResponse.Builder().body(toolUseResponse).build())

            client.send(request(Message.User("Goal: x")))

            assertEquals(listOf(1_000L, 2_000L), sleeps)
            assertEquals(3, api.requestCount)
        }
}
