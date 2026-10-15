package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Model failures, refusals, stuck detection and page-load errors, seen from the host. */
class FailureTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ModelClient, startUrl: String = site.url(), pageLoadTimeoutMs: Long = 60_000) =
        runBlocking { host.run(AgentScript({ model }, tempProfiles(), pageLoadTimeoutMs), TestConfig(startUrl = startUrl)) }

    private fun failingModel(e: ModelException) =
        object : ModelClient {
            override suspend fun send(request: ModelRequest): ModelTurn = throw e
        }

    @Test
    fun `a key revoked mid-run gets the pre-flight key wording, the HTTP body only in the log`() {
        val result = run(failingModel(ModelException("Model/API key rejected: HTTP 401: invalid x-api-key", status = 401)))

        val key = "Anthropic rejected your API key. Copy it again from https://console.anthropic.com/settings/keys into API key."
        assertIs<ExecutionResult.Error>(result)
        assertEquals(key, result.message)
        assertEquals("$key\nCost: \$0", host.notifications.single().message)
        assertTrue(host.logs.any { "invalid x-api-key" in it })
    }

    @Test
    fun `a model 4xx ends the run with Error, the provider's short message and a start-again action`() {
        val result = run(failingModel(ModelException("Model error: HTTP 400: prompt is too long")))

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Anthropic returned an error: HTTP 400: prompt is too long.", result.message)
        assertEquals("Anthropic returned an error: HTTP 400: prompt is too long.\nStart the task again.\nCost: \$0", host.notifications.single().message)
    }

    @Test
    fun `a transient model failure in a Once run says the provider couldn't be reached`() {
        val result = run(failingModel(ModelException("Model error: network error: connection reset", transient = true)))

        assertEquals("Couldn't reach Anthropic (network error).\nTry again later.\nCost: \$0", host.notifications.single().message)
        assertIs<ExecutionResult.Error>(result)
    }

    @Test
    fun `a model that stops without a tool call ends with a start-again action`() {
        val model =
            object : ModelClient {
                override suspend fun send(request: ModelRequest) = ModelTurn(emptyList(), "end_turn", ScriptedModel.MODEL, Usage(1000, 100), raw = null, text = "Done?")
            }

        run(model)

        assertEquals("The model stopped without an answer.\nStart the task again.\nCost: \$0.01", host.notifications.single().message)
    }

    @Test
    fun `a Chrome that won't start asks to check the Chrome install`() {
        // A profile path under a file isn't enough: Chrome on Linux falls back to a temporary profile and starts anyway.
        val model = ScriptedModel()
        val script = AgentScript({ model }, launchBrowser = { _, _ -> throw java.io.IOException("no chrome") })

        val result = runBlocking { host.run(script, TestConfig(startUrl = site.url())) }

        assertIs<ExecutionResult.Error>(result)
        assertEquals(
            "Couldn't start Google Chrome.\nCheck that Chrome is installed and opens normally, then start the task again.\nCost: \$0",
            host.notifications.single().message,
        )
    }

    @Test
    fun `an unexpected error inside the script asks to send the task log`() {
        val result = run(object : ModelClient { override suspend fun send(request: ModelRequest): ModelTurn = error("boom") })

        assertIs<ExecutionResult.Error>(result)
        assertEquals(
            "Something went wrong inside the script.\nStart the task again. If it happens again, send the task log to the developer.\nCost: \$0",
            host.notifications.single().message,
        )
    }

    @Test
    fun `a refusal names the provider and says retrying won't help`() {
        val model =
            object : ModelClient {
                override suspend fun send(request: ModelRequest) =
                    ModelTurn(emptyList(), "refusal", ScriptedModel.MODEL, Usage(1000, 100), raw = null, text = "I can't help with that.")
            }

        val result = run(model)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Anthropic's model declined this goal: I can't help with that.", result.message)
        assertEquals(
            "Anthropic's model declined this goal: I can't help with that.\nChange the goal. Retrying won't help.\nCost: \$0.01",
            host.notifications.single().message,
        )
    }

    @Test
    fun `repeating the same action gets a nudge after 3, and ends as stuck after 3 more`() {
        val addToCart: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Add to cart")}}""")) }
        val model = ScriptedModel(*Array(7) { addToCart })

        val result = run(model, startUrl = site.url("product.html"))

        val nudge = "you are repeating the same action"
        assertTrue(nudge !in model.requests[2].lastText, "no nudge after 2 repeats")
        assertTrue(nudge in model.requests[3].lastText, model.requests[3].lastText)
        assertIs<ExecutionResult.Error>(result)
        val headline = "The agent got stuck repeating the same action on ${site.url("product.html")}."
        assertEquals(headline, result.message)
        assertEquals(6, model.requests.size)
        assertEquals("$headline\nMake the goal more specific, then start the task again.\nCost: \$0.04", host.notifications.single().message)
    }

    @Test
    fun `stuck on a sign-in page points at Forget learned steps and sign out`() {
        val login: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Login")}}""")) }

        run(ScriptedModel(*Array(7) { login }), startUrl = site.url("login.html"))

        assertEquals(
            "The agent got stuck repeating the same action on ${site.url("login.html")}.\n" +
                "Duplicate the task and start the copy. The copy starts without learned steps and signs you out of the site.\nCost: \$0.04",
            host.notifications.single().message,
        )
    }

    @Test
    fun `a page that never loads is a tool error first, then an Error when it repeats`() {
        val navigate: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("navigate", """{"url": "${site.url("never-loads.html")}"}""")) }
        val model =
            ScriptedModel(
                navigate,
                {
                    val result = (it.messages.last() as Message.ToolResults).results.single()
                    assertTrue(result.isError)
                    assertTrue("did not finish loading" in result.text, result.text)
                    navigate(it)
                },
                navigate,
            )

        val result = run(model, pageLoadTimeoutMs = 1_500)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Couldn't load ${site.url("never-loads.html")} (timed out).", result.message)
        assertEquals(2, model.requests.size)
        assertEquals("${result.message}\nThe site may be down. Try again later.\nCost: \$0.01", host.notifications.single().message)
    }

    @Test
    fun `a Start URL that won't load ends the first run before step 1, at no cost`() {
        val model = ScriptedModel()

        val result = run(model, startUrl = "https://practicesoftwaretestin.invalid")

        val message = "Couldn't open https://practicesoftwaretestin.invalid (site not found). Check the Start URL."
        assertIs<ExecutionResult.Error>(result)
        assertEquals(message, result.message)
        assertEquals("$message\nCost: \$0", host.notifications.single().message)
        assertEquals(0, model.requests.size)
    }
}
