package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The task row's one-line status while a run works: progress and cost first, then a plain-words activity. */
class StatusLineTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ModelClient, config: TestConfig = TestConfig(startUrl = site.url())) =
        runBlocking { host.run(AiAgentScript({ model }, tempProfiles()), config) }

    private val finish: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("finish", """{"goal_met": true, "summary": "Yes."}""")) }

    @Test
    fun `a new user sees Starting Chrome, the host, Thinking, then a plain verb and never a tool name`() {
        run(ScriptedModel({ listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) }, finish))

        assertEquals(
            listOf(
                "Checking your API key and model",
                "Starting Chrome",
                "Opening 127.0.0.1",
                "Step 1 of 40 · \$0.00 · Thinking…",
                "Step 1 of 40 · \$0.01 · Clicking \"Long Nose Pliers\"",
                "Step 2 of 40 · \$0.01 · Thinking…",
                "Yes.",
            ),
            host.statuses.distinct(), // the verdict is set twice: as status and as the notification headline
        )
    }

    @Test
    fun `each action reads as a plain verb, a secret by its label and never its value, a URL without its query`() {
        val config = TestConfig(startUrl = site.url(), secrets = listOf("card_pin" to Secret("s3cr3t-pin")))
        run(
            ScriptedModel(
                { listOf(call("type", """{"ref": ${it.ref("textbox", "Search")}, "text": "pliers"}""")) },
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Search")}, "field": "card_pin"}""")) },
                { listOf(call("navigate", """{"url": "${site.url("options.html")}?q=secret#x"}""")) },
                { listOf(call("select", """{"ref": ${it.ref("combobox", "Quantity")}, "option": "Two"}""")) },
                { listOf(call("scroll", """{"direction": "down"}"""), call("wait", """{"seconds": 3}"""), call("read_page")) },
                finish,
            ),
            config,
        )

        val activities = host.statuses.filter { it.startsWith("Step") && !it.endsWith("Thinking…") }.map { it.substringAfterLast(" · ") }
        assertEquals(
            listOf(
                "Typing \"pliers\" into \"Search\"",
                "Filling in your card pin in \"Search\"",
                "Going to 127.0.0.1/options.html",
                "Choosing \"Two\" in \"Quantity\"",
                "Scrolling down",
                "Waiting 3 s",
                "Reading the page",
            ),
            activities,
        )
        assertTrue(host.statuses.none { "s3cr3t" in it })
    }

    @Test
    fun `a replay counts the saved steps at $0, and a recovery says where the saved steps stopped matching`() {
        site.pages["index.html"] = """<html><body><a href="tools.html">Tools</a></body></html>"""
        site.pages["tools.html"] = """<html><body><a href="product.html">Long Nose Pliers</a></body></html>"""
        site.pages["product.html"] = """<html><body><p>Out of stock</p></body></html>"""
        fun click(name: String): suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref("link", name)}}""")) }
        val notMet: suspend (ModelRequest) -> List<ToolCall> = {
            val check = """{"ref": "${it.textRef("StaticText", "Out of stock")}", "condition": "textContains", "expected": "In stock"}"""
            listOf(call("finish", """{"goal_met": false, "summary": "Out of stock.", "readouts": null, "check": $check}"""))
        }
        val model = ScriptedModel(click("Tools"), click("Long Nose Pliers"), notMet, click("Pliers, long nose"), notMet)
        val config = TestConfig(startUrl = site.url(), runMode = RunMode.Repeat, repeatInterval = 7)
        val statuses = mutableListOf<String>()
        runBlocking {
            val script = AiAgentScript({ model }, tempProfiles())
            val provider = host.create()
            script.onStart(config, provider)
            try {
                script.execute(config, provider) {} // learns
                script.execute(config, provider) { statuses += it } // replays
                site.pages["tools.html"] = """<html><body><div><p>New!</p><a href="product.html">Pliers, long nose</a></div></body></html>"""
                script.execute(config, provider) { statuses += it } // recovers
            } finally {
                script.onFinish(config, provider)
            }
        }

        assertEquals(
            listOf(
                "Opening 127.0.0.1",
                "Replaying 1 of 2 · \$0 · Clicking \"Tools\"",
                "Replaying 2 of 2 · \$0 · Clicking \"Long Nose Pliers\"",
                "Replay: checking the result",
                "Not yet · checked hh:mm · next hh:mm · \$0.02 of \$10 total",
                "Opening 127.0.0.1",
                "Replaying 1 of 2 · \$0 · Clicking \"Tools\"",
                "The saved steps stopped matching at step 2. The agent takes over.",
                "Recovering · Step 1 of 40 · \$0.00 · Thinking…",
                "Recovering · Step 1 of 40 · \$0.01 · Clicking \"Pliers, long nose\"",
                "Recovering · Step 2 of 40 · \$0.01 · Thinking…",
                "Not yet · checked hh:mm · next hh:mm · \$0.03 of \$10 total",
            ),
            statuses.map { it.replace(Regex("\\d\\d:\\d\\d"), "hh:mm") },
        )
        assertTrue(statuses.none { "heal" in it.lowercase() || "miss" in it.lowercase() })
    }

    @Test
    fun `a retry replaces Thinking with the provider being busy`() {
        val busy =
            object : ModelClient {
                val inner = ScriptedModel(finish)

                override suspend fun send(request: ModelRequest): ModelTurn {
                    request.onRetry(20_000)
                    return inner.send(request)
                }
            }
        run(busy)

        assertTrue("Step 1 of 40 · \$0.00 · Anthropic is busy, retrying in 20 s" in host.statuses, host.statuses.toString())
    }
}
