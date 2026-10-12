package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.component.userinteraction.UserInteractionCanceledException
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Irreversible steps ask the user first (Confirm / Decline); signing in doesn't. */
class ConfirmationTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel, startPath: String) =
        runBlocking {
            host.run(
                AiAgentScript({ model }, tempProfiles()),
                TestConfig(
                    goal = "Buy one Claw Hammer",
                    startUrl = site.url(startPath),
                    secrets = listOf("email" to Secret(EMAIL), "password" to Secret(PASSWORD)),
                ),
            )
        }

    private fun logIn(button: String, irreversible: Boolean) =
        arrayOf<suspend (ModelRequest) -> List<ToolCall>>(
            { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
            { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Password")}, "field": "password"}""")) },
            { listOf(call("click", """{"ref": ${it.ref("button", button)}, "irreversible": $irreversible, "reason": "log in"}""")) },
        )

    @Test
    fun `declining Place order places no order and makes no further model call`() {
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("button", "Place order")}, "irreversible": true, "reason": "places the order"}""")) },
            )

        val result = run(model, "checkout.html")

        assertEquals("Error: declined by user", result.text)
        assertEquals(1, host.dialogs.size)
        assertEquals(1, model.requests.size)
        assertFalse("/order.html" in site.requested, "the order was submitted")
        assertEquals("Confirmation needed: Place this order on 127.0.0.1?", host.notifications.first().message)
        assertTrue(host.notifications.last().message.startsWith("Declined by user\n"), host.notifications.last().message)
    }

    @Test
    fun `confirming Place order submits it and the run continues`() {
        host.dialogAnswers += confirmClicked
        val model =
            ScriptedModel(
                // No model flag: the heuristic alone asks.
                { listOf(call("click", """{"ref": ${it.ref("button", "Place order")}}""")) },
                {
                    assertTrue("Thank you for your order" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "Ordered."}"""))
                },
            )

        val result = run(model, "checkout.html")

        assertEquals("Success: Goal met", result.text)
        assertEquals(1, host.dialogs.size)
        assertTrue(host.notifications.any { it.message.startsWith("Confirmation needed: ") })
    }

    @Test
    fun `closing the dialog counts as Decline`() {
        host.dialogAnswers += UserInteractionCanceledException()
        val model = ScriptedModel({ listOf(call("click", """{"ref": ${it.ref("button", "Place order")}}""")) })

        assertEquals("Error: declined by user", run(model, "checkout.html").text)
        assertFalse("/order.html" in site.requested)
    }

    @Test
    fun `a model-flagged Login submit after type_secret is not confirmed and the exemption is logged`() {
        val model =
            ScriptedModel(
                *logIn("Login", irreversible = true),
                {
                    assertTrue("My account" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "Logged in."}"""))
                },
            )

        val result = run(model, "login.html")

        assertEquals("Success: Goal met", result.text)
        assertEquals(0, host.dialogs.size)
        assertTrue(host.logs.any { "model flag cleared: sign-in submit" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a sign-up form with two password inputs keeps the model flag`() {
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Password")}, "field": "password"}""")) },
                { listOf(call("click", """{"ref": ${it.ref("button", "Create account")}, "irreversible": true, "reason": "creates an account"}""")) },
            )

        assertEquals("Error: declined by user", run(model, "signup.html").text)
        assertEquals(1, host.dialogs.size)
        assertFalse(host.logs.any { "model flag cleared" in it })
    }

    @Test
    fun `a heuristic match asks even on a sign-in form`() {
        site.pages["login.html"] = javaClass.getResource("/fixtures/login.html")!!.readText().replace(">Login</button>", ">Submit</button>")
        host.dialogAnswers += confirmClicked
        val model =
            ScriptedModel(
                *logIn("Submit", irreversible = true),
                { listOf(call("finish", """{"goal_met": true, "summary": "Logged in."}""")) },
            )

        assertEquals("Success: Goal met", run(model, "login.html").text)
        assertEquals(1, host.dialogs.size)
    }

    @Test
    fun `typing into a field named like an irreversible action asks only when Enter submits it`() {
        site.pages["lookup.html"] = """<html><body><form action="order.html"><input aria-label="Order number"></form></body></html>"""
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                { listOf(call("type", """{"ref": ${it.ref("textbox", "Order number")}, "text": "A-1"}""")) },
                { listOf(call("type", """{"ref": ${it.ref("textbox", "Order number")}, "text": "A-1\n"}""")) },
            )

        assertEquals("Error: declined by user", run(model, "lookup.html").text)
        assertEquals(2, model.requests.size, "the plain type asked nothing")
        assertEquals(1, host.dialogs.size)
        assertFalse("/order.html" in site.requested)
    }

    @Test
    fun `every click on an off-domain page asks`() {
        host.dialogAnswers += declineClicked
        // redirect.html sends the browser to localhost, which is not an allowed domain.
        val model = ScriptedModel({ listOf(call("click", """{"ref": ${it.ref("checkbox", "Remember me")}}""")) })

        assertEquals("Error: declined by user", run(model, "redirect.html").text)
        assertEquals(1, host.dialogs.size)
    }

    @Test
    fun `the dialog asks one plain question with the button text, the reason and two buttons, and logs the raw step`() {
        site.pages["checkout.html"] =
            javaClass.getResource("/fixtures/checkout.html")!!.readText().replace(">Place order<", ">Place your order – $12.49<")
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                {
                    listOf(
                        call(
                            "click",
                            """{"ref": ${it.ref("button", "Place your order – $12.49")}, "irreversible": true, "reason": "This places the order."}""",
                        ),
                    )
                },
            )

        run(model, "checkout.html")

        val html = host.dialogs.single()
        assertTrue("AI Browser Agent · needs your OK" in html, html)
        assertTrue("Place this order on 127.0.0.1?" in html, html)
        assertTrue("It will press “Place your order – $12.49”" in html, html)
        assertTrue("This places the order." in html, html)
        assertTrue(html.indexOf(">No, stop here</a>") in 0 until html.indexOf(">Yes, place this order</a>"), html)
        assertTrue("Closing this window, or leaving the Tasks screen, counts as No. The task waits until you answer." in html, html)
        assertFalse("button &quot;" in html || "button \"" in html || site.url("checkout.html") in html || "<script" in html, html)
        assertTrue(host.logs.any { "button \"Place your order – $12.49\"" in it && site.url("checkout.html") in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `without a reason the dialog says why the step was flagged`() {
        host.dialogAnswers += declineClicked
        val model = ScriptedModel({ listOf(call("click", """{"ref": ${it.ref("button", "Place order")}}""")) })

        run(model, "checkout.html")

        assertTrue("The agent gave no reason. It flagged this step because the button says “Place order”." in host.dialogs.single(), host.dialogs.single())
    }

    @Test
    fun `a type step quotes the text it will send`() {
        site.pages["lookup.html"] = """<html><body><form action="order.html"><input aria-label="Order number"></form></body></html>"""
        host.dialogAnswers += declineClicked
        val model = ScriptedModel({ listOf(call("type", """{"ref": ${it.ref("textbox", "Order number")}, "text": "A-1 & <b>\n"}""")) })

        run(model, "lookup.html")

        val html = host.dialogs.single()
        assertTrue("Send this text on 127.0.0.1?" in html, html)
        assertTrue("It will send: “A-1 &amp; &lt;b&gt;”" in html, html)
        assertTrue(">Yes, send this text</a>" in html, html)
    }

    @Test
    fun `the dialog HTML-escapes the button text and the reason`() {
        site.pages["checkout.html"] =
            javaClass.getResource("/fixtures/checkout.html")!!.readText().replace(">Place order<", ">Place &lt;b&gt;order&lt;/b&gt; &amp; pay<")
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                {
                    listOf(
                        call(
                            "click",
                            """{"ref": ${it.ref("button", "Place <b>order</b> & pay")}, "irreversible": true, "reason": "<script>alert(1)</script>"}""",
                        ),
                    )
                },
            )

        run(model, "checkout.html")

        val html = host.dialogs.single()
        assertTrue("Place &lt;b&gt;order&lt;/b&gt; &amp; pay" in html, html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html, html)
        assertFalse("<script>alert" in html || "<b>order" in html, html)
    }

    /** ExecutionResult has no equals: compare `Kind: message`. */
    private val ExecutionResult?.text get() = "${this?.javaClass?.simpleName}: ${this?.message}"

    private companion object {
        const val EMAIL = "customer@practicesoftwaretesting.com"
        const val PASSWORD = "welcome01"
    }
}
