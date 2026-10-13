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
                AgentScript({ model }, tempProfiles()),
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
    fun `a label is a free word the agent knows by its lowercase name`() {
        val model =
            ScriptedModel(
                {
                    assertTrue("type_secret (by name): email, my_password" in it.lastText, it.lastText)
                    listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}"""))
                },
                {
                    assertTrue("{{secret:email}}" in it.lastText, it.lastText)
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        runBlocking {
            host.run(
                AgentScript({ model }, tempProfiles()),
                TestConfig(
                    goal = "Log in with my email",
                    startUrl = site.url("login.html"),
                    secrets = listOf(" Email " to Secret(EMAIL), "My Password" to Secret(PASSWORD)),
                ),
            )
        }

        assertEquals(2, model.requests.size)
    }

    @Test
    fun `a failure reason that carries a typed secret is scrubbed from the logs and the result`() {
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                { listOf(call("fail", """{"reason": "no account for $EMAIL"}""")) },
            )

        val result = run(model)

        assertIs<ExecutionResult.Error>(result)
        assertFalse(EMAIL in result.message, result.message)
        assertFalse(host.logs.any { EMAIL in it }, host.logs.joinToString("\n"))
        assertTrue(host.logs.any { "no account for {{secret:email}}" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a secret longer than the snapshot's value cap never reaches the model, not even in part`() {
        val long = "x9" + "Q".repeat(60) + "tail-" + "Z".repeat(60)
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                {
                    assertTrue("{{secret:email}}" in it.lastText, it.lastText)
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        runBlocking {
            host.run(
                AgentScript({ model }, tempProfiles()),
                TestConfig(goal = "Log in", startUrl = site.url("login.html"), secrets = listOf("email" to Secret(long))),
            )
        }

        val outbound = model.requests.joinToString("\n") { it.text() }
        assertFalse("QQQQQQQQ" in outbound, outbound)
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
    fun `tasks with the same goal and URL but another account get their own Chrome profile`() {
        val profiles = tempProfiles()
        fun runAs(email: String) =
            runBlocking {
                host.run(
                    AgentScript({ ScriptedModel({ listOf(call("fail", """{"reason": "x"}""")) }) }, profiles),
                    TestConfig(goal = "Log in", startUrl = site.url("login.html"), secrets = listOf("email" to Secret(email))),
                )
            }

        runAs(EMAIL)
        runAs("someone-else@example.com")
        runAs(EMAIL)

        assertEquals(2, profiles.toFile().list()!!.size, "one profile per account, reused by the same account")
    }

    private fun runOn(page: String, model: ScriptedModel) =
        runBlocking {
            site.pages["me.html"] = """<html><body>$page<input aria-label="Email address"></body></html>"""
            host.run(
                AgentScript({ model }, tempProfiles()),
                TestConfig(goal = "Show my balance", startUrl = site.url("me.html"), secrets = listOf("email" to Secret(EMAIL))),
            )
        }

    private val typeEmail: suspend (ModelRequest) -> List<ToolCall> = {
        listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}"""))
    }

    @Test
    fun `a Readout showing a revealed secret is scrubbed in the notification`() {
        val model =
            ScriptedModel(
                typeEmail,
                {
                    val readout = """{"label": "Email", "ref": "${it.textRef("StaticText", "{{secret:email}}")}", "value": "$EMAIL"}"""
                    listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": [$readout], "check": null}"""))
                },
            )

        assertIs<ExecutionResult.Success>(runOn("<p>Email:</p><p>$EMAIL</p>", model))

        val message = host.notifications.single().message
        assertTrue("Email: {{secret:email}}\n" in message, message)
        assertFalse(EMAIL in message, message)
    }

    @Test
    fun `a Readout located by a revealed secret is refused, so no secret lands in the cache`() {
        val model =
            ScriptedModel(
                typeEmail,
                {
                    val readout = """{"label": "Balance", "ref": "${it.textRef("StaticText", "EUR 5.00")}", "value": "EUR 5.00"}"""
                    listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": [$readout], "check": null}"""))
                },
                {
                    val result = it.lastResults.single()
                    assertTrue(result.isError && "secret" in result.text, result.text)
                    listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": null, "check": null}"""))
                },
            )

        runOn("<h2>$EMAIL</h2><p>EUR 5.00</p>", model)

        assertEquals(3, model.requests.size)
        val cache = runBlocking { host.create().preference().getString(ReplayCache.key("Show my balance", site.url("me.html"), setOf("email"))) }
        assertFalse(cache.orEmpty().contains(EMAIL), cache)
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

    private companion object {
        const val EMAIL = "customer@practicesoftwaretesting.com"
        const val PASSWORD = "welcome01"
        const val API_KEY = "sk-ant-test-key-123"
    }
}
