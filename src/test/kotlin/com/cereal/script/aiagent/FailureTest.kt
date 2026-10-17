package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmToolCall
import com.cereal.sdk.component.llm.LlmException
import com.cereal.sdk.component.llm.LlmResponse
import com.cereal.sdk.component.llm.StopReason
import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.time.Duration.Companion.seconds
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

    private fun run(respond: suspend (LlmRequest) -> LlmResponse, startUrl: String = site.url(), pageLoadTimeoutMs: Long = 60_000) =
        runBlocking { host.run(AgentScript(tempProfiles(), pageLoadTimeoutMs).also { host.llm.responder = respond }, TestConfig(startUrl = startUrl)) }

    private fun failingModel(e: LlmException): suspend (LlmRequest) -> LlmResponse = { throw e }

    /** One model failure in a Once run: the Error's message, then the notification's headline, action and cost lines. */
    private fun failure(e: LlmException): Pair<String, String> {
        val result = assertIs<ExecutionResult.Error>(run(failingModel(e)))
        return result.message to host.notifications.single().message
    }

    @Test
    fun `no AI provider set up ends the run with the client's message and no action`() {
        val (headline, message) = failure(LlmException.NotConfigured())

        assertEquals("No AI provider is set up. Choose one in Settings → AI.", headline)
        assertEquals("$headline\nCost: \$0", message)
    }

    @Test
    fun `a rejected key ends the run with the client's message and no action`() {
        val key = "The AI provider rejected the API key. Check it in Settings → AI."

        assertEquals(key to "$key\nCost: \$0", failure(LlmException.Authentication(key)))
    }

    @Test
    fun `a rejected request names the AI provider, quotes its text and says start again`() {
        val headline = "The AI provider returned an error: prompt is too long."

        assertEquals(headline to "$headline\nStart the task again.\nCost: \$0", failure(LlmException.InvalidRequest("prompt is too long")))
    }

    @Test
    fun `a rate limit in a Once run fails at once with the client's message and no action`() {
        val busy = "The AI provider is busy. Try again in a minute."

        assertEquals(busy to "$busy\nCost: \$0", failure(LlmException.RateLimited(30.seconds, busy)))
    }

    @Test
    fun `an unavailable provider in a Once run fails at once with the client's message and no action`() {
        val down = "Couldn't reach the AI provider. Check your connection."

        assertEquals(down to "$down\nCost: \$0", failure(LlmException.Unavailable(down)))
    }

    @Test
    fun `an unavailable provider without a message says it couldn't reach the AI provider`() {
        assertEquals("Couldn't reach the AI provider.", failure(LlmException.Unavailable()).first)
    }

    @Test
    fun `a model that stops without a tool call ends with a start-again action`() {
        run({ response(text = "Done?") })

        assertEquals("The model stopped without an answer.\nStart the task again.\nCost: \$0.01", host.notifications.single().message)
    }

    @Test
    fun `a Chrome that won't start asks to check the Chrome install`() {
        // A profile path under a file isn't enough: Chrome on Linux falls back to a temporary profile and starts anyway.
        val model = ScriptedModel()
        val script = AgentScript(launchBrowser = { _, _ -> throw java.io.IOException("no chrome") }).also { host.llm.responder = model::respond }

        val result = runBlocking { host.run(script, TestConfig(startUrl = site.url())) }

        assertIs<ExecutionResult.Error>(result)
        assertEquals(
            "Couldn't start Google Chrome.\nCheck that Chrome is installed and opens normally, then start the task again.\nCost: \$0",
            host.notifications.single().message,
        )
    }

    @Test
    fun `an unexpected error inside the script asks to send the task log`() {
        val result = run({ error("boom") })

        assertIs<ExecutionResult.Error>(result)
        assertEquals(
            "Something went wrong inside the script.\nStart the task again. If it happens again, send the task log to the developer.\nCost: \$0",
            host.notifications.single().message,
        )
    }

    @Test
    fun `a refusal says the model declined, without naming a provider, and that retrying won't help`() {
        val result = run({ response(text = "I can't help with that.", stopReason = StopReason.Refusal) })

        assertIs<ExecutionResult.Error>(result)
        assertEquals("The model declined this goal: I can't help with that.", result.message)
        assertEquals(
            "The model declined this goal: I can't help with that.\nChange the goal. Retrying won't help.\nCost: \$0.01",
            host.notifications.single().message,
        )
    }

    @Test
    fun `repeating the same action gets a nudge after 3, and ends as stuck after 3 more`() {
        val addToCart: suspend (LlmRequest) -> List<LlmToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Add to cart")}}""")) }
        val model = ScriptedModel(*Array(7) { addToCart })

        val result = run(model::respond, startUrl = site.url("product.html"))

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
        val login: suspend (LlmRequest) -> List<LlmToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Login")}}""")) }

        run(ScriptedModel(*Array(7) { login })::respond, startUrl = site.url("login.html"))

        assertEquals(
            "The agent got stuck repeating the same action on ${site.url("login.html")}.\n" +
                "Pick Forget learned steps and sign out in the task's menu, then start the task again.\nCost: \$0.04",
            host.notifications.single().message,
        )
    }

    @Test
    fun `a page that never loads is a tool error first, then an Error when it repeats`() {
        val navigate: suspend (LlmRequest) -> List<LlmToolCall> = { listOf(call("navigate", """{"url": "${site.url("never-loads.html")}"}""")) }
        val model =
            ScriptedModel(
                navigate,
                {
                    val result = it.lastResults.single()
                    assertTrue(result.isError)
                    assertTrue("did not finish loading" in result.text, result.text)
                    navigate(it)
                },
                navigate,
            )

        val result = run(model::respond, pageLoadTimeoutMs = 1_500)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Couldn't load ${site.url("never-loads.html")} (timed out).", result.message)
        assertEquals(2, model.requests.size)
        assertEquals("${result.message}\nThe site may be down. Try again later.\nCost: \$0.01", host.notifications.single().message)
    }

    @Test
    fun `a Start URL that won't load ends the first run before step 1, at no cost`() {
        val model = ScriptedModel()

        val result = run(model::respond, startUrl = "https://practicesoftwaretestin.invalid")

        val message = "Couldn't open https://practicesoftwaretestin.invalid (site not found). Check the Start URL."
        assertIs<ExecutionResult.Error>(result)
        assertEquals(message, result.message)
        assertEquals("$message\nCost: \$0", host.notifications.single().message)
        assertEquals(0, model.requests.size)
    }
}
