package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cookie consent banners are rejected by the script (autoconsent) before the model sees the page. */
class ConsentTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(page: String): ScriptedModel {
        val model = ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "Read it."}""")) })
        runBlocking { host.run(AgentScript({ model }, tempProfiles()), TestConfig(goal = "Read the headline", startUrl = site.url(page))) }
        return model
    }

    @Test
    fun `a known banner is rejected before the first snapshot and logged`() {
        val first = run("consent-onetrust.html").requests.first().lastText

        assertTrue("Cookies rejected" in first, first)
        assertFalse("Accept All Cookies" in first, "the banner is gone from the first snapshot:\n$first")
        assertTrue("/rejected" in site.requested)
        assertFalse("/accepted" in site.requested)
        assertTrue(host.logs.any { it == "INFO Rejected cookies (onetrust)" }, host.logs.joinToString("\n"))
    }

    @Test
    fun `the model is told to reject a banner that is left, never accept all`() {
        val model = run("index.html")
        assertTrue(
            "If a cookie consent banner blocks the page, reject it or choose necessary-only; never accept all." in model.requests.first().system,
        )
    }

    @Test
    fun `a banner that can only be accepted is left alone`() {
        val first = run("consent-accept-only.html").requests.first().lastText

        assertTrue("Accept all cookies" in first, "the model still sees the banner:\n$first")
        assertFalse("/accepted" in site.requested)
        assertFalse(host.logs.any { "Rejected cookies" in it })
    }

    @Test
    fun `a page without a banner adds no wait`() {
        runBlocking {
            val browser = Browser.launch(tempProfiles())
            try {
                browser.navigate(site.url()) // warm up
                val start = System.nanoTime()
                browser.navigate(site.url("product.html"))
                val ms = (System.nanoTime() - start) / 1_000_000
                // navigate's own fixed waits are 600 ms; autoconsent's search for a banner runs ~10 s on such a page.
                assertTrue(ms < 1_500, "took $ms ms")
            } finally {
                browser.close()
            }
        }
    }

    @Test
    fun `a banner provider with no open banner stops costing a wait once the search for it ends`() {
        runBlocking {
            val browser = Browser.launch(tempProfiles())
            try {
                browser.navigate(site.url("consent-saved.html")) // a choice saved earlier: the provider loads, its banner stays hidden
                kotlinx.coroutines.delay(7_000) // autoconsent looks for the banner for ~5 s
                val start = System.nanoTime()
                browser.wait(1)
                val ms = (System.nanoTime() - start) / 1_000_000
                // wait's own 1 s plus settle's 600 ms of fixed waits; a stuck "busy" would add the 3 s cap.
                assertTrue(ms < 2_500, "took $ms ms")
            } finally {
                browser.close()
            }
        }
    }
}
