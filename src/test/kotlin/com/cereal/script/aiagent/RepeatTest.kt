package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.models.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repeat mode: the first run learns with the model, later runs replay the cache with zero model calls. */
class RepeatTest {
    private val site = FixtureServer()
    private val host = Host()
    private val profiles = tempProfiles()

    @AfterTest
    fun tearDown() = site.close()

    private fun config(
        goal: String = GOAL,
        startPath: String = "",
        secrets: List<Pair<String, Secret>> = emptyList(),
        reLearn: Boolean = false,
        maxTaskCost: Double = 10.0,
    ) = TestConfig(
        goal = goal,
        startUrl = site.url(startPath),
        runMode = RunMode.Repeat,
        repeatInterval = 7,
        secrets = secrets,
        reLearn = reLearn,
        maxTaskCost = maxTaskCost,
    )

    /** One task start: onStart, then [block] calls execute like the host's Loop does (without the delay), then onFinish. */
    private fun task(
        model: ModelClient,
        config: TestConfig = config(),
        block: suspend (execute: suspend () -> ExecutionResult) -> Unit,
    ) = runBlocking {
        val script = AiAgentScript({ model }, profiles)
        val provider = host.create()
        assertTrue(script.onStart(config, provider))
        try {
            block { script.execute(config, provider) {} }
        } finally {
            script.onFinish(config, provider)
        }
    }

    private fun stock(text: String) {
        site.pages["product.html"] = javaClass.getResource("/fixtures/product.html")!!.readText().replace("In stock", text)
    }

    private fun cache(goal: String = GOAL, startPath: String = "", secretNames: String = ""): String? =
        runBlocking {
            host.create().preference().getString("replay:" + sha256(goal + "\n" + site.url(startPath).removeSuffix("/") + "\n" + secretNames))
        }

    /** Learns: open the product, then finish "not met" with a Check on the stock text. */
    private fun learning() =
        ScriptedModel(
            {
                assertTrue("Run mode: Repeat" in it.lastText, it.lastText)
                listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}"""))
            },
            { listOf(call("finish", notMet(it))) },
        )

    private fun notMet(request: ModelRequest) =
        """{"goal_met": false, "summary": "Out of stock.", "readouts": null,
            "check": {"ref": "${request.textRef("StaticText", "Out of stock")}", "condition": "textContains", "expected": "In stock"}}"""

    @Test
    fun `a monitor learns once, replays with zero model calls, then notifies once when the stock flips`() {
        stock("Out of stock")
        val model = learning()

        task(model) { execute ->
            val first = execute()
            assertIs<ExecutionResult.Loop>(first)
            assertEquals(7_000, first.delay)
            assertTrue(host.notifications.isEmpty(), host.notifications.toString())
            assertNotNull(cache(), "the replay cache was written")

            repeat(2) { assertIs<ExecutionResult.Loop>(execute()) }
            assertEquals(2, model.requests.size, "replays made no model request")
            assertTrue(host.notifications.isEmpty())

            stock("In stock")
            assertIs<ExecutionResult.Success>(execute())
            assertEquals(2, model.requests.size)
            assertEquals("Goal met\nCost: \$0", host.notifications.single().message)
        }
    }

    @Test
    fun `a Chrome that was closed or crashed is started again at the next iteration`() {
        stock("Out of stock")
        val model = learning()

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            val chrome = ProcessHandle.allProcesses().toList().filter { "--user-data-dir=$profiles" in it.info().commandLine().orElse("") }
            assertTrue(chrome.isNotEmpty())
            chrome.forEach { it.destroyForcibly() }
            chrome.forEach { it.onExit().get() }

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, model.requests.size, "the relaunched Chrome replays")
        }
    }

    @Test
    fun `a Check that disagrees with goal_met is rejected twice, then the run reports but caches nothing`() {
        stock("Out of stock")
        // "Out of stock" contains "Out of stock": true on the page, while the model says not met.
        var wrong = ""
        val disagreeing = { r: ModelRequest ->
            if (wrong.isEmpty()) wrong = notMet(r).replace("\"In stock\"", "\"Out of stock\"")
            listOf(call("finish", wrong))
        }
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) },
                disagreeing,
                {
                    val result = it.lastResults.single()
                    assertTrue(result.isError && "Check" in result.text && "goal_met" in result.text, result.text)
                    disagreeing(it)
                },
                disagreeing,
            )

        task(model) { execute -> assertIs<ExecutionResult.Loop>(execute()) }

        assertEquals(4, model.requests.size)
        assertNull(cache())
        assertTrue(host.logs.any { "replay cache not written" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a first run that finds the goal already met notifies, stops and caches the steps without a Check`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) },
                {
                    val check = """{"ref": "${it.textRef("StaticText", "In stock")}", "condition": "textContains", "expected": "In stock"}"""
                    listOf(call("finish", """{"goal_met": true, "summary": "In stock.", "readouts": null, "check": $check}"""))
                },
            )

        task(model) { execute -> assertIs<ExecutionResult.Success>(execute()) }

        assertEquals("Goal met\nCost: \$0.01", host.notifications.single().message)
        val cached = Json.parseToJsonElement(assertNotNull(cache())).jsonObject
        assertEquals(1, cached.getValue("steps").jsonArray.size)
        assertTrue(cached["check"] == null || cached["check"] is JsonNull, cached.toString())
    }

    @Test
    fun `the cache survives a task restart`() {
        stock("Out of stock")
        task(learning()) { execute -> assertIs<ExecutionResult.Loop>(execute()) }

        val afterRestart = ScriptedModel()
        task(afterRestart) { execute -> assertIs<ExecutionResult.Loop>(execute()) }

        assertTrue(afterRestart.requests.isEmpty())
    }

    @Test
    fun `Re-learn removes the entry at task start, and an edited goal has its own entry`() {
        stock("Out of stock")
        task(learning()) { execute -> assertIs<ExecutionResult.Loop>(execute()) }
        assertNotNull(cache())

        val edited = "$GOAL, please"
        assertNull(cache(edited))
        val relearning = learning()
        task(relearning, config(reLearn = true)) { execute ->
            assertNull(cache(), "Re-learn deleted the entry at task start")
            assertIs<ExecutionResult.Loop>(execute())
        }
        assertEquals(2, relearning.requests.size)
        assertNotNull(cache())
    }

    @Test
    fun `a replayed login asks for no confirmation and the cache holds placeholders, not secrets`() {
        val secrets = listOf("email" to Secret(EMAIL), "password" to Secret(PASSWORD))
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Password")}, "field": "password"}""")) },
                { listOf(call("click", """{"ref": ${it.ref("button", "Login")}, "irreversible": true, "reason": "log in"}""")) },
                {
                    val check = """{"ref": "${it.textRef("StaticText", "My account")}", "condition": "textContains", "expected": "2 new invoices"}"""
                    listOf(call("finish", """{"goal_met": false, "summary": "No new invoices.", "readouts": null, "check": $check}"""))
                },
            )

        task(model, config(goal = "Tell me when I have 2 new invoices", startPath = "login.html", secrets = secrets)) { execute ->
            repeat(3) { assertIs<ExecutionResult.Loop>(execute()) }
        }

        assertEquals(4, model.requests.size)
        assertEquals(0, host.dialogs.size)
        val cached = assertNotNull(cache("Tell me when I have 2 new invoices", "login.html", "email,password"))
        assertTrue("{{secret:email}}" in cached && "{{secret:password}}" in cached, cached)
        assertFalse(EMAIL in cached || PASSWORD in cached, cached)
        assertFalse(host.logs.any { EMAIL in it || PASSWORD in it })
    }

    @Test
    fun `a replayed Place order asks every time`() {
        repeat(2) { host.dialogAnswers += confirmClicked }
        host.dialogAnswers += declineClicked
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("button", "Place order")}, "irreversible": true, "reason": "orders"}""")) },
                {
                    val check = """{"ref": "${it.textRef("StaticText", "Thank you for your order.")}", "condition": "textContains", "expected": "shipped"}"""
                    listOf(call("finish", """{"goal_met": false, "summary": "Not shipped.", "readouts": null, "check": $check}"""))
                },
            )

        task(model, config(goal = "Order a Claw Hammer until one ships", startPath = "checkout.html")) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, host.dialogs.size)
            val declined = execute()
            assertIs<ExecutionResult.Error>(declined)
            assertEquals("declined by user", declined.message)
        }

        assertEquals(3, host.dialogs.size)
        assertEquals(2, model.requests.size)
        assertEquals(2, site.requested.count { it == "/order.html" })
    }

    @Test
    fun `the task cap counts every iteration and ends the task with no further Loop, until a restart`() {
        stock("Out of stock")
        // Finish without a Check: Repeat rejects it twice, then reports "not met" uncached, so every iteration learns.
        val noCheck: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("finish", """{"goal_met": false, "summary": "No.", "readouts": null, "check": null}""")) }
        val model = ScriptedModel(noCheck, noCheck, noCheck, noCheck)

        task(model, config(maxTaskCost = 0.02)) { execute ->
            assertIs<ExecutionResult.Loop>(execute()) // 3 steps, $0.018
            val stopped = execute() // 1 step brings the task to $0.024
            assertIs<ExecutionResult.Error>(stopped)
            assertEquals("Step/cost limit reached: max \$ per task (\$0.02)", stopped.message)
        }
        assertEquals(4, model.requests.size)
        assertEquals("Step/cost limit reached: max \$ per task (\$0.02)\nCost: \$0.01", host.notifications.single().message)

        val restarted = ScriptedModel(noCheck, noCheck, noCheck)
        task(restarted, config(maxTaskCost = 0.02)) { execute -> assertIs<ExecutionResult.Loop>(execute()) }
        assertEquals(3, restarted.requests.size, "a restart resets the task total")
    }

    @Test
    fun `a replay that lands on another page than recorded is a miss and the model is asked again`() {
        stock("Out of stock")
        val model = ScriptedModel({ listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) }, { listOf(call("finish", notMet(it))) })

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            // The first link now leads elsewhere: the xpath fallback finds it, but the page after the click is not the recorded one.
            site.pages["index.html"] = "<html><body><nav><a href=\"order.html\">Hammers</a></nav></body></html>"
            assertIs<ExecutionResult.Error>(execute()) // the model has no turn left
        }

        assertEquals(3, model.requests.size)
        assertTrue(host.logs.any { "replay miss: step 1 of 1 (click): the page is 127.0.0.1/order.html, not 127.0.0.1/product.html" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a value revealed in an earlier iteration stays scrubbed in later ones`() {
        val noCheck: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("finish", """{"goal_met": false, "summary": "No.", "readouts": null, "check": null}""")) }
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                noCheck, noCheck, noCheck,
                {
                    assertTrue("Signed in as {{secret:email}}" in it.lastText, it.lastText)
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        task(model, config(goal = "Watch my account", startPath = "login.html", secrets = listOf("email" to Secret(EMAIL)))) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            // The next iteration's page shows the email without the model typing it again.
            site.pages["login.html"] = "<html><body><h1>Account</h1><p>Signed in as $EMAIL</p></body></html>"
            execute()
        }

        assertFalse(model.requests.any { EMAIL in it.text() })
    }

    private val down: suspend (ModelRequest) -> List<ToolCall> = { throw ModelException("Model error: HTTP 503: overloaded", transient = true) }

    // Finish without a Check: rejected twice, then reported "not met" uncached, so the next iteration asks the model again.
    private val noCheck: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("finish", """{"goal_met": false, "summary": "No.", "readouts": null, "check": null}""")) }

    @Test
    fun `a transient failure loops quietly, three in a row end the task, and a good iteration resets the count`() {
        stock("Out of stock")
        val model = ScriptedModel(down, down, noCheck, noCheck, noCheck, down, down, down)

        task(model) { execute ->
            repeat(2) { assertEquals(7_000, assertIs<ExecutionResult.Loop>(execute()).delay) }
            assertIs<ExecutionResult.Loop>(execute()) // a good iteration
            repeat(2) { assertIs<ExecutionResult.Loop>(execute()) }
            assertTrue(host.notifications.isEmpty(), host.notifications.toString())
            assertTrue(host.logs.any { "transient failure 2 of 3" in it }, host.logs.joinToString("\n"))

            val ended = execute()
            assertIs<ExecutionResult.Error>(ended)
            assertEquals("Model error: HTTP 503: overloaded", ended.message)
        }
        assertEquals(8, model.requests.size)
        assertEquals("Model error: HTTP 503: overloaded\nCost: \$0", host.notifications.single().message)
    }

    @Test
    fun `a non-transient failure ends a monitor at once`() {
        val model = ScriptedModel({ throw ModelException("Model/API key rejected: HTTP 401: invalid x-api-key") })

        task(model) { execute -> assertIs<ExecutionResult.Error>(execute()) }

        assertEquals("Model/API key rejected: HTTP 401: invalid x-api-key\nCost: \$0", host.notifications.single().message)
    }

    private companion object {
        const val EMAIL = "customer@practicesoftwaretesting.com"
        const val PASSWORD = "welcome01"
        const val GOAL = "Notify me when Long Nose Pliers is in stock"

        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
