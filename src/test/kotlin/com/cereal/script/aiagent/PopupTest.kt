package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tabs the agent's own action opens are followed (the current tab), and it gets back when they close. */
class PopupTest {
    private val site = FixtureServer()
    private val host = Host()
    private val profiles = tempProfiles()

    init {
        site.pages["index.html"] =
            """<html><body><h1>Shop</h1>
            <a href="product.html" target="_blank">Long Nose Pliers</a>
            <button onclick="setTimeout(() => window.open('product.html'), 1000)">Open later</button>
            <button onclick="window.open('closing.html')">Sign in</button>
            </body></html>"""
        site.pages["closing.html"] = """<html><body><h1>Signing in</h1><script>setTimeout(() => window.close(), 500)</script></body></html>"""
    }

    @AfterTest
    fun tearDown() = site.close()

    private fun config(runMode: RunMode = RunMode.Once, start: String = "") =
        TestConfig(goal = GOAL, startUrl = site.url(start), runMode = runMode, repeatInterval = 7)

    private fun task(
        model: ModelClient,
        config: TestConfig = config(),
        block: suspend (execute: suspend () -> ExecutionResult) -> Unit,
    ) = runBlocking {
        // Chrome blocks a popup no click opened; a user who allowed popups on a site lets it open tabs on load.
        val allowPopups: suspend (java.nio.file.Path, Long) -> Browser = { dir, timeout ->
            val prefs = dir.resolve("Default/Preferences")
            java.nio.file.Files.createDirectories(prefs.parent)
            if (!java.nio.file.Files.exists(prefs)) {
                java.nio.file.Files.writeString(
                    prefs,
                    """{"profile": {"content_settings": {"exceptions": {"popups": {"http://127.0.0.1:*,*": {"setting": 1}}}}}}""",
                )
            }
            Browser.launch(dir, timeout)
        }
        val script = AgentScript({ model }, profiles, launchBrowser = allowPopups)
        val provider = host.create()
        assertTrue(script.onStart(config, provider))
        try {
            block { script.execute(config, provider) {} }
        } finally {
            script.onFinish(config, provider)
        }
    }

    private fun click(role: String, name: String): suspend (ModelRequest) -> List<ToolCall> = { listOf(call("click", """{"ref": ${it.ref(role, name)}}""")) }

    private val met: suspend (ModelRequest) -> List<ToolCall> = {
        val check = """{"ref": "${it.textRef("StaticText", "In stock")}", "condition": "textContains", "expected": "In stock"}"""
        listOf(call("finish", """{"goal_met": true, "summary": "In stock.", "readouts": null, "check": $check}"""))
    }

    @Test
    fun `a target=_blank link is followed into the new tab`() {
        val model =
            ScriptedModel(
                click("link", "Long Nose Pliers"),
                {
                    assertTrue("Now in a new tab: ${site.url("product.html")}" in it.lastText, it.lastText)
                    met(it)
                },
            )
        task(model) { execute -> assertIs<ExecutionResult.Success>(execute()) }
        assertTrue(host.logs.any { "Now in a new tab: ${site.url("product.html")}" in it }, host.logs.joinToString("\n"))
    }

    @Test
    fun `a window_open delayed by 1 s is followed on the next snapshot`() {
        val model =
            ScriptedModel(
                click("button", "Open later"),
                { listOf(call("wait", """{"seconds": 1}""")) },
                {
                    assertTrue("Now in a new tab: ${site.url("product.html")}" in it.lastText, it.lastText)
                    met(it)
                },
            )
        task(model) { execute -> assertIs<ExecutionResult.Success>(execute()) }
    }

    @Test
    fun `a tab the start page opens on load is ignored`() {
        site.pages["onload.html"] = """<html><body><a href="product.html">Long Nose Pliers</a><script>window.open('ad.html')</script></body></html>"""
        site.pages["ad.html"] = """<html><body><h1>Ad</h1></body></html>"""
        val model =
            ScriptedModel(
                click("link", "Long Nose Pliers"),
                {
                    assertTrue("new tab" !in it.lastText && "url: ${site.url("product.html")}" in it.lastText, it.lastText)
                    met(it)
                },
            )
        task(model, config(start = "onload.html")) { execute -> assertIs<ExecutionResult.Success>(execute()) }
        assertTrue("/ad.html" in site.requested, "the site did open its tab")
    }

    companion object {
        const val GOAL = "Notify me when Long Nose Pliers is in stock"
    }
}
