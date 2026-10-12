package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Chrome profile belongs to the task (and its secrets): rewording the goal keeps sign-ins, a new task starts clean. */
class ProfileTest {
    private val site = FixtureServer().apply { pages["session.html"] = SESSION_PAGE }
    private val profiles = tempProfiles()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(host: Host, goal: String, vararg turns: suspend (ModelRequest) -> List<ToolCall>) =
        runBlocking { host.run(AiAgentScript({ ScriptedModel(*turns) }, profiles), TestConfig(goal = goal, startUrl = site.url("session.html"))) }

    private fun finish(): List<ToolCall> = listOf(call("finish", """{"goal_met": true, "summary": "ok"}"""))

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
