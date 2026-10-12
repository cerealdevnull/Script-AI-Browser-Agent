package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-flight key and model lookup, before Chrome starts. */
class PreflightTest {
    private val api = MockWebServer().apply { start() }
    private val host = Host()
    private val base = api.url("/").toString().removeSuffix("/")

    @AfterTest
    fun tearDown() = api.close()

    private fun reply(code: Int, body: String = "{}") = api.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun run(config: TestConfig = TestConfig(model = "claude-sonet-5-5")) =
        runBlocking { host.run(AiAgentScript({ AnthropicClient(base, "sk-wrong", it.model()!!) { } }, tempProfiles()), config) }

    @Test
    fun `a wrong key is reported with the key link before Chrome starts`() {
        reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")

        val result = run()

        val key = "Anthropic rejected your API key. Copy it again from https://console.anthropic.com/settings/keys into API key."
        assertEquals(key, assertIs<ExecutionResult.Error>(result).message)
        assertEquals("$key\nCost: \$0", host.notifications.single().message)
        assertEquals(listOf("Checking your API key and model", key), host.statuses)
        assertEquals("/v1/models/claude-sonet-5-5", api.takeRequest().url.encodedPath)
    }

    @Test
    fun `a misspelt model gets the model message`() {
        reply(404)

        val result = run()

        assertEquals(
            "Anthropic has no model called `claude-sonet-5-5`. Clear Model to use the recommended one, or check the spelling.",
            assertIs<ExecutionResult.Error>(result).message,
        )
    }

    @Test
    fun `an unreachable server fails a Once run`() {
        repeat(4) { reply(503) }

        val result = run()

        assertEquals("Couldn't reach Anthropic (HTTP 503). Try again later.", assertIs<ExecutionResult.Error>(result).message)
        assertTrue("Starting Chrome" !in host.statuses)
    }

    @Test
    fun `an unreachable server makes a Repeat run retry at the next interval`() {
        repeat(12) { reply(503) }
        val config = TestConfig(model = "claude-sonet-5-5", runMode = RunMode.Repeat)
        val script = AiAgentScript({ AnthropicClient(base, "k", it.model()!!) { } }, tempProfiles())
        val provider = host.create()

        runBlocking {
            script.onStart(config, provider)
            repeat(2) { assertIs<ExecutionResult.Loop>(script.execute(config, provider) {}) }
            assertIs<ExecutionResult.Error>(script.execute(config, provider) {}, "three in a row end the task")
        }
        assertEquals("Couldn't reach Anthropic (HTTP 503). Try again later.\nCost: \$0", host.notifications.single().message)
    }

    @Test
    fun `OpenAI-compatible servers look the model up in the list`() =
        runBlocking {
            fun client(model: String) = OpenAiCompatibleClient(base, "k", model) { }
            val list = """{"data":[{"id":"gpt-5.6-luna"},{"id":"models/gemini-3.8-flash"},{"id":"x/no-tools","supported_parameters":["temperature"]}]}"""
            repeat(4) { reply(200, list) }
            assertNull(client("gpt-5.6-luna").check())
            assertNull(client("gemini-3.8-flash").check())
            assertEquals(Preflight.UnknownModel, client("gpt-6-sol").check())
            assertEquals(Preflight.NoTools, client("x/no-tools").check())
            assertEquals("/models", api.takeRequest().url.encodedPath)

            reply(404)
            assertNull(client("llama3").check(), "a Custom server without a models endpoint skips the check")
            reply(403)
            assertEquals(Preflight.BadKey, client("gpt-5.6-luna").check())
        }
}
