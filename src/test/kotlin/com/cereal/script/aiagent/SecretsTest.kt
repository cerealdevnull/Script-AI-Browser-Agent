package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Secret slots: typed by name through type_secret, never shown to the model or the logs. */
class SecretsTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel) =
        runBlocking {
            host.run(
                AiAgentScript({ model }, tempProfiles()),
                TestConfig(
                    goal = "Log in",
                    startUrl = site.url("login.html"),
                    apiKey = Secret(API_KEY),
                    secrets = listOf("email" to Secret(EMAIL), "password" to Secret(PASSWORD)),
                ),
            )
        }

    @Test
    fun `logs in with two slots without the values reaching the model or the logs`() {
        val model =
            ScriptedModel(
                {
                    assertTrue("email" in it.lastText && "password" in it.lastText, "the goal message names the slots")
                    listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}"""))
                },
                {
                    assertTrue("{{secret:email}}" in it.lastText, "the typed email shows as a placeholder:\n${it.lastText}")
                    listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Password")}, "field": "password"}"""))
                },
                { listOf(call("click", """{"ref": ${it.ref("button", "Login")}}""")) },
                {
                    assertTrue("My account" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "Logged in."}"""))
                },
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        val outbound = model.requests.joinToString("\n") { it.text() }
        for (value in listOf(EMAIL, PASSWORD, API_KEY)) {
            assertFalse(value in outbound, "model requests contain $value")
            assertFalse(host.logs.any { value in it }, "logs contain $value")
        }
    }

    @Test
    fun `type_secret into a non-text element or an unknown slot is a tool error`() {
        val model =
            ScriptedModel(
                {
                    listOf(
                        call("type_secret", """{"ref": ${it.ref("button", "Login")}, "field": "email"}"""),
                        call("type_secret", """{"ref": ${it.ref("checkbox", "Remember me")}, "field": "email"}"""),
                        call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "pin"}"""),
                    )
                },
                {
                    val results = (it.messages.last() as Message.ToolResults).results
                    assertTrue(results.all { r -> r.isError }, results.joinToString { r -> r.text })
                    assertTrue("not an editable text input" in results[0].text, results[0].text)
                    assertTrue("not an editable text input" in results[1].text, results[1].text)
                    assertEquals("error: unknown secret 'pin'; available: email, password", results[2].text)
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        run(model)

        assertEquals(2, model.requests.size)
    }

    @Test
    fun `an error carrying the API key or a revealed secret is logged scrubbed`() {
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                { error("upstream echoed $API_KEY and $EMAIL") },
            )

        val result = run(model)

        assertIs<ExecutionResult.Error>(result)
        assertTrue(host.logs.any { "upstream echoed {{api_key}} and {{secret:email}}" in it }, host.logs.joinToString("\n"))
        assertFalse(host.logs.any { API_KEY in it || EMAIL in it })
    }

    /** Everything a request carries as text. */
    private fun ModelRequest.text() =
        system + "\n" +
            messages.joinToString("\n") { m ->
                when (m) {
                    is Message.User -> m.text
                    is Message.ToolResults -> m.results.joinToString("\n") { it.text }
                    is Message.Assistant -> m.turn.toolCalls.joinToString { "${it.name}${it.args}" }
                }
            }

    private companion object {
        const val EMAIL = "customer@practicesoftwaretesting.com"
        const val PASSWORD = "welcome01"
        const val API_KEY = "sk-ant-test-key-123"
    }
}
