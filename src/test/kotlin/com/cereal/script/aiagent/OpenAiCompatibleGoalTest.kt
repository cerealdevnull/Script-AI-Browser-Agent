package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A Once goal through the script's real OpenAI-compatible adapter, with the model faked at the HTTP level. */
class OpenAiCompatibleGoalTest {
    private val site = FixtureServer()
    private val api = MockWebServer()
    private val host = Host()

    @AfterTest
    fun tearDown() {
        site.close()
        api.close()
    }

    @Test
    fun `a local server with no API key reaches the goal`() {
        api.dispatcher = chatCompletions(
            { snapshot -> "click" to """{"ref": ${Regex("""\[(\d+)] link "Long Nose Pliers"""").find(snapshot)!!.groupValues[1]}}""" },
            { _ -> "finish" to """{"goal_met": true, "summary": "Long Nose Pliers is in stock."}""" },
        )
        api.start()
        val config =
            TestConfig(provider = Provider.Custom, baseUrl = api.url("/v1").toString(), apiKey = null, model = "llama3", startUrl = site.url())

        val result = runBlocking { host.run(AiAgentScript(profilesDir = tempProfiles()), config) }

        assertIs<ExecutionResult.Success>(result)
        assertEquals("Goal met\nLong Nose Pliers is in stock.\n2 steps, $0.00", host.notifications.single().message)
        val requests = List(api.requestCount) { api.takeRequest() }
        assertEquals(listOf("/v1/chat/completions", "/v1/chat/completions"), requests.map { it.url.encodedPath })
        requests.forEach { assertNull(it.headers["Authorization"]) }
    }

    /** Answers each request with one tool call, chosen from the latest message the model was sent. */
    private fun chatCompletions(vararg turns: (String) -> Pair<String, String>) =
        object : Dispatcher() {
            private var n = 0

            override fun dispatch(request: RecordedRequest): MockResponse {
                val last = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["messages"]!!.jsonArray.last().jsonObject
                val (name, args) = turns[n++](last["content"]!!.jsonPrimitive.content)
                val message =
                    buildJsonObject {
                        put("role", "assistant")
                        put(
                            "tool_calls",
                            Json.parseToJsonElement(
                                """[{"id":"call_$n","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(args)}}}]""",
                            ),
                        )
                    }
                val body = """{"model":"llama3","choices":[{"finish_reason":"tool_calls","message":$message}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
                return MockResponse.Builder().body(body).build()
            }
        }
}
