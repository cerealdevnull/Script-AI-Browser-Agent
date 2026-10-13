package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An open dropdown or dialog is printed at the top of the snapshot, so a long page can't cut it off. */
class OpenPopupTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(page: String, model: ScriptedModel) {
        site.pages["popup.html"] = page
        runBlocking { host.run(AgentScript({ model }, tempProfiles()), TestConfig(goal = "Pick size 43", startUrl = site.url("popup.html"))) }
    }

    // ~600 links: far more than the snapshot limit.
    private val filler = (1..600).joinToString("\n") { "<a href=\"#p$it\">Recommended product number $it</a>" }

    @Test
    fun `an open listbox rendered past the limit is shown once at the top, and its options can be clicked`() {
        val page =
            """
            <!doctype html><html><head><title>Shoe</title></head><body>
            <h1>Shoe</h1>
            <button aria-expanded="true" aria-controls="sizes" aria-haspopup="listbox">Maat kiezen</button>
            <p id="picked">Nothing picked</p>
            $filler
            <ul id="sizes" role="listbox" aria-label="Sizes">
              <li role="option" aria-disabled="true">42</li>
              <li role="option" onclick="document.getElementById('picked').textContent = 'Picked 43'">43</li>
            </ul>
            </body></html>
            """.trimIndent()
        val model =
            ScriptedModel(
                {
                    val snapshot = it.lastText
                    assertTrue(snapshot.indexOf("— open popup —") in 0 until snapshot.indexOf("Maat kiezen"), snapshot)
                    assertEquals(1, Regex("""option "43"""").findAll(snapshot).count(), "printed once")
                    assertTrue(Regex("""option "42" disabled""").containsMatchIn(snapshot), snapshot)
                    assertTrue("Recommended product number 1\"" in snapshot, "the rest of the page follows")
                    listOf(call("click", """{"ref": ${it.ref("option", "43")}}"""))
                },
                {
                    assertTrue("Picked 43" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": false, "summary": "Picked."}"""))
                },
            )
        run(page, model)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `an open dialog at the end of a long page is shown at the top`() {
        val page =
            """
            <!doctype html><html><head><title>Shoe</title></head><body>
            <h1>Shoe</h1>
            $filler
            <div role="dialog" aria-label="Added to bag"><p>Added to your bag</p><button>Checkout</button></div>
            </body></html>
            """.trimIndent()
        val model =
            ScriptedModel({
                val snapshot = it.lastText
                val marker = snapshot.indexOf("— open popup —")
                assertTrue(marker in 0 until snapshot.indexOf("heading \"Shoe\""), snapshot)
                assertTrue(snapshot.indexOf("button \"Checkout\"") in marker until snapshot.indexOf("heading \"Shoe\""), snapshot)
                assertEquals(1, Regex("""button "Checkout"""").findAll(snapshot).count(), "printed once")
                listOf(call("finish", """{"goal_met": false, "summary": "Seen."}"""))
            })
        run(page, model)
    }

    @Test
    fun `a page without an open popup has no marker`() {
        val model =
            ScriptedModel({
                assertTrue("open popup" !in it.lastText, it.lastText)
                listOf(call("finish", """{"goal_met": false, "summary": "Seen."}"""))
            })
        run("<!doctype html><html><body><h1>Shoe</h1><button aria-expanded=\"false\" aria-controls=\"x\">Maat kiezen</button><ul id=\"x\" role=\"listbox\" hidden></ul></body></html>", model)
    }
}
