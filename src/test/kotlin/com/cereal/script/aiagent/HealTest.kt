package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A replay miss: the model resumes from the current page, and the cache tail from the failed step is rewritten. */
class HealTest {
    private val site = FixtureServer()
    private val host = Host()
    private val profiles = tempProfiles()

    init {
        site.pages["index.html"] = """<html><body><a href="tools.html">Tools</a></body></html>"""
        site.pages["tools.html"] = """<html><body><h1>Tools</h1><a href="product.html">Long Nose Pliers</a></body></html>"""
        stock("Out of stock")
    }

    @AfterTest
    fun tearDown() = site.close()

    private fun config(runMode: RunMode = RunMode.Repeat, maxSteps: Int = 40) =
        TestConfig(goal = GOAL, startUrl = site.url(""), runMode = runMode, repeatInterval = 7, maxSteps = maxSteps)

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

    private fun cacheSteps(): JsonArray =
        runBlocking {
            val key = "replay:" + sha256(GOAL + "\n" + site.url("").removeSuffix("/") + "\n")
            Json.parseToJsonElement(host.create().preference().getString(key)!!).jsonObject.getValue("steps").jsonArray
        }

    private fun click(name: String): suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref("link", name)}}""")) }

    private val notMet: suspend (ModelRequest) -> List<ToolCall> = {
        val check = """{"ref": "${it.textRef("StaticText", "Out of stock")}", "condition": "textContains", "expected": "In stock"}"""
        listOf(call("finish", """{"goal_met": false, "summary": "Out of stock.", "readouts": null, "check": $check}"""))
    }

    /** Learns Tools -> Long Nose Pliers -> a Check on the stock text (3 model requests). */
    private fun learning(vararg healTurns: suspend (ModelRequest) -> List<ToolCall>) =
        ScriptedModel(click("Tools"), click("Long Nose Pliers"), notMet, *healTurns)

    @Test
    fun `a step whose target is gone resumes the model mid-run, rewrites only the tail, and the next run replays`() {
        val model =
            learning(
                {
                    val goalMessage = it.lastText
                    assertTrue("url: ${site.url("tools.html")}" in goalMessage, goalMessage)
                    assertTrue("1. click link \"Tools\"" in goalMessage, goalMessage)
                    assertTrue("step 2 of 2 (click link \"Long Nose Pliers\")" in goalMessage, goalMessage)
                    click("Pliers, long nose")(it)
                },
                notMet,
            )

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            val learned = cacheSteps()

            site.pages["tools.html"] = """<html><body><h1>Tools</h1><div><p>New!</p><a href="product.html">Pliers, long nose</a></div></body></html>"""
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(5, model.requests.size)
            val healed = cacheSteps()
            assertEquals(learned[0], healed[0], "steps before the failed step are unchanged")
            assertNotEquals(learned[1], healed[1])
            assertTrue("Pliers, long nose" in healed[1].toString(), healed.toString())

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(5, model.requests.size, "the run after a heal replays with zero model requests")
        }
        assertTrue(host.logs.any { "replay miss: step 2 of 2 (click): no unique match" in it }, host.logs.joinToString("\n"))
        assertTrue(host.logs.any { "run: Repeat, healed, 2 steps, \$0.01, Goal not met" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a step that lands on another page keeps the step with its new page and heals from there`() {
        val model =
            learning(
                {
                    assertTrue("url: ${site.url("pliers-v2.html")}" in it.lastText, it.lastText)
                    assertTrue("1. click link \"Tools\"\n2. click link \"Long Nose Pliers\"" in it.lastText, it.lastText)
                    notMet(it)
                },
            )

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            val learned = cacheSteps()

            site.pages["pliers-v2.html"] = site.pages.getValue("product.html")
            site.pages["tools.html"] = """<html><body><h1>Tools</h1><a href="pliers-v2.html">Long Nose Pliers</a></body></html>"""
            assertIs<ExecutionResult.Loop>(execute())
            val healed = cacheSteps()
            assertEquals(learned[0], healed[0])
            assertTrue("pliers-v2.html" in healed[1].jsonObject.getValue("urlPattern").toString(), healed.toString())

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(4, model.requests.size)
        }
        assertTrue(host.logs.any { "replay miss: step 2 of 2 (click): the page is" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a Check whose target is gone heals on the last page, keeps every step and records the new Check`() {
        val model =
            learning(
                {
                    assertTrue("url: ${site.url("product.html")}" in it.lastText, it.lastText)
                    assertTrue("2. click link \"Long Nose Pliers\"" in it.lastText && "check" in it.lastText, it.lastText)
                    notMet(it)
                },
            )

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            val learned = cacheSteps()

            site.pages["product.html"] = "<html><body><div><h2>Pliers</h2><p>Out of stock</p></div></body></html>"
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(learned, cacheSteps())

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(4, model.requests.size)
        }
        assertTrue(host.logs.any { "replay miss: the check's target does not match exactly one node" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `two equally matching elements are a miss, not a click`() {
        val model =
            learning(
                {
                    assertEquals(1, site.requested.count { r -> r == "/product.html" }, "neither link was clicked")
                    assertTrue("url: ${site.url("tools.html")}" in it.lastText, it.lastText)
                    listOf(call("fail", """{"reason": "which pliers?"}"""))
                },
            )

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            site.pages["tools.html"] =
                """<html><body><h1>Tools</h1><a href="product.html">Long Nose Pliers</a> <a href="product.html">Long Nose Pliers</a></body></html>"""
            assertIs<ExecutionResult.Error>(execute())
        }
        assertEquals(4, model.requests.size)
        assertTrue(host.logs.any { "replay miss: step 2 of 2 (click): no unique match" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a missing Readout element is a miss, the healed value is reported and never a blank`() {
        val price: suspend (ModelRequest) -> List<ToolCall> = {
            val text = Regex("""\[t\d+] StaticText "(Price: [^"]+)"""").find(it.lastText)!!.groupValues[1]
            val readout = """{"label": "Price", "ref": "${it.textRef("StaticText", text)}", "value": "$text"}"""
            listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": [$readout], "check": null}"""))
        }
        val model = ScriptedModel(click("Tools"), click("Long Nose Pliers"), price, price)

        task(model, config(RunMode.Once)) { execute -> assertIs<ExecutionResult.Success>(execute()) }
        site.pages["product.html"] = "<html><body><div><h2>Pliers</h2><p>Price: \$15.00</p></div></body></html>"
        task(model, config(RunMode.Once)) { execute -> assertIs<ExecutionResult.Success>(execute()) }

        assertEquals("Found it.\nPrice: Price: \$15.00\nCost: \$0.01 (the page changed, so it re-learned part of it)", host.notifications.last().message)
        assertTrue(host.logs.any { "replay miss: readout 'Price' does not match exactly one node" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a replay that stops matching after a redesign and ends not met, where the learned run met it, points at Forget learned steps`() {
        val price: suspend (ModelRequest) -> List<ToolCall> = {
            val text = Regex("""\[t\d+] StaticText "(Price: [^"]+)"""").find(it.lastText)!!.groupValues[1]
            val readout = """{"label": "Price", "ref": "${it.textRef("StaticText", text)}", "value": "$text"}"""
            listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": [$readout], "check": null}"""))
        }
        val notFound: suspend (ModelRequest) -> List<ToolCall> = {
            listOf(call("finish", """{"goal_met": false, "summary": "Couldn't find the pliers.", "readouts": null, "check": null}"""))
        }
        val model = ScriptedModel(click("Tools"), click("Long Nose Pliers"), price, notFound)

        task(model, config(RunMode.Once)) { execute -> assertIs<ExecutionResult.Success>(execute()) }
        site.pages["tools.html"] = """<html><body><h1>Tools</h1><p>New look!</p></body></html>"""
        task(model, config(RunMode.Once)) { execute -> assertIs<ExecutionResult.Success>(execute()) }

        assertEquals(
            "Couldn't find the pliers.\nPick “Forget learned steps” in On next start, then start the task again.\n" +
                "Cost: \$0.01 (the page changed, so it re-learned part of it)",
            host.notifications.last().message,
        )
    }

    @Test
    fun `a replay that ends not met where the learned run was not met suggests no reset`() {
        val model = learning()
        task(model) { execute -> assertIs<ExecutionResult.Loop>(execute()) }
        task(model, config(RunMode.Once)) { execute -> assertIs<ExecutionResult.Success>(execute()) }
        assertFalse(host.notifications.any { "Forget" in it.message }, host.notifications.joinToString { it.message })
    }

    @Test
    fun `an unexpected page error during replay heals instead of ending the monitor`() {
        val original = site.pages.getValue("product.html")
        val model =
            learning(
                {
                    site.pages["product.html"] = original
                    listOf(call("navigate", """{"url": "${site.url("product.html")}"}"""))
                },
                notMet,
            )

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            // Breaks the script's in-page lookups: evaluating the Check throws.
            site.pages["product.html"] = original.replace("<body>", "<body><script>Element.prototype.querySelectorAll = () => { throw new Error('boom') }</script>")
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(5, model.requests.size)
        }
        assertTrue(host.logs.any { "replay miss: the check" in it && "boom" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `another kind of element where the learned target was is a miss, not a guess`() {
        site.pages["index.html"] = """<html><body><form><input aria-label="Email address"></form><p>Status:</p><p>Out</p></body></html>"""
        val out: suspend (ModelRequest) -> List<ToolCall> = {
            val check = """{"ref": "${it.textRef("StaticText", "Out")}", "condition": "textContains", "expected": "In"}"""
            listOf(call("finish", """{"goal_met": false, "summary": "Out.", "readouts": null, "check": $check}"""))
        }
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}""")) },
                out,
                {
                    assertFalse("value=" in it.lastText, "the secret was typed into another element:\n${it.lastText}")
                    out(it)
                },
            )
        val config = TestConfig(goal = GOAL, startUrl = site.url(""), runMode = RunMode.Repeat, repeatInterval = 7, secrets = listOf("email" to Secret("me@example.com")))

        task(model, config) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            // Same xpath, but a search box instead of the email field.
            site.pages["index.html"] = """<html><body><form><input type="search" aria-label="Search"></form><p>Status:</p><p>Out</p></body></html>"""
            assertIs<ExecutionResult.Loop>(execute())
        }
        assertEquals(3, model.requests.size)
        assertTrue(host.logs.any { "replay miss: step 1 of 1 (type_secret): no unique match" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a heal that finds the goal met keeps the learned Check, so the next run still replays`() {
        val met: suspend (ModelRequest) -> List<ToolCall> = {
            val check = """{"ref": "${it.textRef("StaticText", "In stock")}", "condition": "textContains", "expected": "In stock"}"""
            listOf(call("finish", """{"goal_met": true, "summary": "In stock.", "readouts": null, "check": $check}"""))
        }
        val model = learning(click("Pliers, long nose"), met)

        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            site.pages["tools.html"] = """<html><body><h1>Tools</h1><div><p>New!</p><a href="product.html">Pliers, long nose</a></div></body></html>"""
            stock("In stock")
            assertIs<ExecutionResult.Success>(execute())
            assertEquals(5, model.requests.size)

            assertIs<ExecutionResult.Success>(execute())
            assertEquals(5, model.requests.size, "the run after the heal replays")
        }
    }

    @Test
    fun `a heal that hits a cap leaves the cache as it was`() {
        task(learning()) { execute -> assertIs<ExecutionResult.Loop>(execute()) }
        val learned = cacheSteps()

        site.pages["tools.html"] = """<html><body><h1>Tools</h1><div><p>New!</p><a href="product.html">Pliers, long nose</a></div></body></html>"""
        val healing = ScriptedModel(click("Pliers, long nose"), notMet)
        task(healing, config(maxSteps = 1)) { execute ->
            val stopped = execute()
            assertIs<ExecutionResult.Error>(stopped)
            assertEquals("Stopped after 1 step, the Max steps per run limit.", stopped.message)
        }

        assertEquals(1, healing.requests.size)
        assertEquals(learned, cacheSteps())
        assertTrue(host.notifications.last().message.startsWith("Stopped after 1 step, the Max steps per run limit.\n"))
    }

    private companion object {
        const val GOAL = "Notify me when Long Nose Pliers is in stock"

        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
