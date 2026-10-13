package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Frames: same-site iframes are part of the snapshot and of replay; other iframes are labelled placeholders. */
class FrameTest {
    private val site = FixtureServer()
    private val host = Host()
    private val profiles = tempProfiles()

    init {
        // The page is on 127.0.0.1; localhost is another site, so that frame is out-of-process.
        site.pages["shop.html"] =
            """<html><body><h1>Shop</h1><button>Top</button>
              <iframe src="stock.html"></iframe>
              <iframe src="${site.url("ad.html").replace("127.0.0.1", "localhost")}"></iframe></body></html>"""
        stockFrame("Out of stock")
        site.pages["ad.html"] = """<html><body><button>Ad</button></body></html>"""
        site.pages["inner.html"] = """<html><body><button>Inner</button></body></html>"""
        // Like w3schools' iframeResult: an about:blank frame the page fills by script, and a srcdoc frame.
        site.pages["blank.html"] =
            """<html><body><h1>Shop</h1><iframe id="f"></iframe>
              <iframe srcdoc="<button>Srcdoc</button>"></iframe>
              <script>document.getElementById('f').contentDocument.body.innerHTML =
                '<h2>Long Nose Pliers</h2><dl><dt>Stock:</dt><dd>Out of stock</dd></dl><button>Refresh</button>'</script></body></html>"""
    }

    @AfterTest
    fun tearDown() = site.close()

    private fun stockFrame(stock: String) {
        site.pages["stock.html"] =
            """<html><body><h2>Long Nose Pliers</h2><dl><dt>Stock:</dt><dd>$stock</dd></dl>
              <button>Refresh</button>
              <iframe src="inner.html"></iframe></body></html>"""
    }

    @Test
    fun `same-site frame content is indented under its Iframe with continued refs, other frames are labelled not visible`() =
        runBlocking {
            val browser = Browser.launch(profiles)
            try {
                browser.navigate(site.url("shop.html"))
                val snapshot = browser.snapshot()
                val lines = snapshot.lines()
                val iframe = lines.indexOfFirst { it.trim() == "Iframe" }
                assertTrue(iframe >= 0, snapshot)
                val indent = lines[iframe].takeWhile { it == ' ' }.length
                val refresh = lines.indexOfFirst { "button \"Refresh\"" in it }
                assertTrue(refresh > iframe && lines[refresh].takeWhile { it == ' ' }.length > indent, snapshot)
                assertTrue("[1] button \"Top\"" in snapshot && "[2] button \"Refresh\"" in snapshot, snapshot)
                assertTrue("Iframe (nested, not visible)" in snapshot, snapshot)
                assertTrue("Iframe (cross-origin: localhost, not visible)" in snapshot, snapshot)
                assertTrue("Inner" !in snapshot && "\"Ad\"" !in snapshot, snapshot)
            } finally {
                browser.close()
            }
        }

    @Test
    fun `about blank and srcdoc frames inherit the page's site and are shown inline`() =
        runBlocking {
            val browser = Browser.launch(profiles)
            try {
                browser.navigate(site.url("blank.html"))
                val snapshot = browser.snapshot()
                assertTrue("button \"Refresh\"" in snapshot && "button \"Srcdoc\"" in snapshot, snapshot)
                assertTrue("not visible" !in snapshot, snapshot)
            } finally {
                browser.close()
            }
        }

    private val goal = "Tell me whether Long Nose Pliers is in stock"

    private fun config(page: String = "shop.html") = TestConfig(goal = goal, startUrl = site.url(page), runMode = RunMode.Repeat, repeatInterval = 7)

    private fun task(model: ModelClient, page: String = "shop.html", block: suspend (execute: suspend () -> ExecutionResult) -> Unit) =
        runBlocking {
            val script = AgentScript({ model }, profiles)
            val provider = host.create()
            assertTrue(script.onStart(config(page), provider))
            try {
                block { script.execute(config(page), provider) {} }
            } finally {
                script.onFinish(config(page), provider)
            }
        }

    private val clickRefresh: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref("button", "Refresh")}}""")) }

    private val notMet: suspend (ModelRequest) -> List<ToolCall> = {
        val ref = it.textRef("StaticText", "Out of stock")
        val check = """{"ref": "$ref", "condition": "textContains", "expected": "In stock"}"""
        val readouts = """[{"label": "Stock", "ref": "$ref", "value": "Out of stock"}]"""
        listOf(call("finish", """{"goal_met": false, "summary": "Out of stock.", "readouts": $readouts, "check": $check}"""))
    }

    @Test
    fun `a learned click, Check and Readout inside a frame replay with zero model calls`() {
        val model = ScriptedModel(clickRefresh, notMet)
        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, model.requests.size)
            assertTrue("Iframe marked \"not visible\" can't be read or used" in model.requests[0].system, model.requests[0].system)

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, model.requests.size, "the replay makes no model call")
        }
    }

    @Test
    fun `a learned click, Check and Readout inside a script-filled about blank frame replay with zero model calls`() {
        val model = ScriptedModel(clickRefresh, notMet)
        task(model, "blank.html") { execute ->
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, model.requests.size)

            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(2, model.requests.size, "the replay makes no model call")
        }
    }

    @Test
    fun `a frame whose src moved to another path is a miss that heals`() {
        val model = ScriptedModel(clickRefresh, notMet, clickRefresh, notMet)
        task(model) { execute ->
            assertIs<ExecutionResult.Loop>(execute())

            site.pages["stock-v2.html"] = site.pages.getValue("stock.html")
            site.pages["shop.html"] = site.pages.getValue("shop.html").replace("\"stock.html\"", "\"stock-v2.html\"")
            assertIs<ExecutionResult.Loop>(execute())
            assertEquals(4, model.requests.size, "the moved frame made the replay heal")
        }
        assertTrue(host.logs.any { "replay miss: step 1 of 1 (click)" in it }, host.logs.joinToString("\n"))
    }
}
