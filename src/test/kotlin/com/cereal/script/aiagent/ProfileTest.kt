package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmToolCall
import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Chrome profile belongs to the task (and its secrets): rewording the goal keeps sign-ins, a new task starts clean. */
class ProfileTest {
    private val site = FixtureServer().apply { pages["session.html"] = SESSION_PAGE }
    private val profiles = tempProfiles()

    @AfterTest
    fun tearDown() = site.close()

    private fun config(goal: String) = TestConfig(goal = goal, startUrl = site.url("session.html"))

    private fun run(host: Host, goal: String, vararg turns: suspend (LlmRequest) -> List<LlmToolCall>) =
        runBlocking { host.run(AgentScript(profiles).also { host.llm.responder = (ScriptedModel(*turns))::respond }, config(goal)) }

    private fun finish(): List<LlmToolCall> = listOf(call("finish", """{"goal_met": true, "summary": "ok"}"""))

    @Test
    fun `a reworded goal keeps the sign-in, a new task with the same settings starts signed out`() {
        val task = Host()
        assertIs<ExecutionResult.Success>(
            run(
                task,
                "Sign in",
                {
                    assertTrue("Signed out" in it.lastText, it.lastText)
                    listOf(call("click", """{"ref": ${it.ref("button", "Sign in")}}"""))
                },
                { finish() },
            ),
        )

        assertIs<ExecutionResult.Success>(
            run(task, "Sign in to the shop", { assertTrue("Signed in" in it.lastText, it.lastText).let { finish() } }),
        )

        assertIs<ExecutionResult.Success>(
            run(Host(), "Sign in to the shop", { assertTrue("Signed out" in it.lastText, it.lastText).let { finish() } }),
        )
    }

    @Test
    fun `stuck on a sign-in page signs out, so the next start signs in from scratch`() {
        val task = Host()
        val signIn: suspend (LlmRequest) -> List<LlmToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Sign in")}}""")) }
        assertIs<ExecutionResult.Success>(run(task, "Sign in", signIn, { finish() }))

        // The page's path says sign-in, and the model keeps clicking the same button.
        site.pages["sign-in.html"] = SESSION_PAGE
        val stuck = runBlocking { task.run(AgentScript(profiles).also { task.llm.responder = (ScriptedModel(*Array(7) { signIn }))::respond }, TestConfig(goal = "Sign in", startUrl = site.url("sign-in.html"))) }
        assertIs<ExecutionResult.Error>(stuck)
        assertEquals(SIGNED_OUT, task.notifications.last().message.lines()[1])
        assertFalse("rofile" in SIGNED_OUT, "user text names the profile")
        assertTrue(profiles.toFile().listFiles().isNullOrEmpty(), "the task's Chrome profiles are gone")

        assertIs<ExecutionResult.Success>(run(task, "Sign in", { assertTrue("Signed out" in it.lastText, it.lastText).let { finish() } }))
    }
}

private val SESSION_PAGE =
    """
    <!doctype html><html><body>
    <p id="state"></p><button onclick="document.cookie='session=1; max-age=86400; path=/'; show()">Sign in</button>
    <script>
      function show() { document.getElementById('state').textContent = document.cookie.includes('session=1') ? 'Signed in' : 'Signed out'; }
      show();
    </script>
    </body></html>
    """.trimIndent()
