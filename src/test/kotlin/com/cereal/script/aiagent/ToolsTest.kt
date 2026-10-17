package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.ImageMediaType
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rest of the tool allow-list: select, scroll, go_back, wait and the screenshot fallback. */
class ToolsTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel, startPath: String) =
        runBlocking {
            host.run(
                AgentScript(tempProfiles()).also { host.llm.responder = model::respond },
                TestConfig(goal = "x", startUrl = site.url(startPath), secrets = listOf("email" to Secret("jane@example.com"))),
            )
        }

    @Test
    fun `a newline in type presses Enter and submits the form`() {
        val model =
            ScriptedModel(
                { listOf(call("type", """{"ref": ${it.ref("textbox", "Email address")}, "text": "customer@practicesoftwaretesting.com"}""")) },
                { listOf(call("type", """{"ref": ${it.ref("textbox", "Password")}, "text": "welcome01\n"}""")) },
                {
                    assertTrue("My account" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "Logged in."}"""))
                },
            )

        run(model, "login.html")

        assertEquals(3, model.requests.size)
    }

    @Test
    fun `a later call in the same step whose refs an earlier action renumbered is not run`() {
        // Typing adds a Clear button before Search: the Search button's ref moves from [2] to [3].
        site.pages["search.html"] =
            """<html><body><input aria-label="Query" id="q"><span id="slot"></span>
            <button onclick="out.textContent = 'searched'">Search</button><p id="out"></p>
            <script>q.addEventListener('input', () => { if (slot.firstChild) return; const b = document.createElement('button'); b.textContent = 'Clear'; b.onclick = () => { out.textContent = 'cleared' }; slot.append(b) })</script>
            </body></html>"""
        val model =
            ScriptedModel(
                {
                    listOf(
                        call("type", """{"ref": ${it.ref("textbox", "Query")}, "text": "pliers"}"""),
                        call("click", """{"ref": ${it.ref("button", "Search")}}"""),
                    )
                },
                {
                    val (typed, clicked) = it.lastResults
                    assertFalse(typed.isError, typed.text)
                    assertTrue(clicked.isError && "stale" in clicked.text, clicked.text)
                    assertFalse("cleared" in it.lastText, "the click hit the button that took the old ref:\n${it.lastText}")
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        run(model, "search.html")

        assertEquals(2, model.requests.size)
    }

    @Test
    fun `the tool set is exactly the allow-list`() {
        assertEquals(
            setOf("read_page", "click", "type", "type_secret", "select", "scroll", "navigate", "go_back", "wait", "screenshot", "finish", "fail"),
            Agent.TOOLS.map { it.name }.toSet(),
        )
    }

    @Test
    fun `select, scroll, wait, go_back and screenshot`() {
        val model =
            ScriptedModel(
                { listOf(call("select", """{"ref": ${it.ref("combobox", "Quantity")}, "option": "Three"}""")) },
                {
                    assertFalse(it.lastResults.single().isError, it.lastText)
                    assertTrue("Chosen: 3" in it.lastText, it.lastText)
                    listOf(
                        call("select", """{"ref": ${it.ref("combobox", "Quantity")}, "option": "Twelve"}"""),
                        call("scroll", """{"direction": "down"}"""),
                        call("scroll", """{"direction": "sideways"}"""),
                        call("wait", """{"seconds": 1}"""),
                        call("screenshot"),
                    )
                },
                {
                    val (badOption, scroll, badScroll, wait, shot) = it.lastResults
                    assertTrue(badOption.isError && "Twelve" in badOption.text, badOption.text)
                    assertFalse(scroll.isError, scroll.text)
                    assertTrue(badScroll.isError, badScroll.text)
                    assertFalse(wait.isError, wait.text)
                    assertFalse(shot.isError, shot.text)
                    assertEquals(ImageMediaType.Jpeg, it.lastImages.single().mediaType, "the screenshot rides beside the results")
                    listOf(call("navigate", """{"url": "${site.url("product.html")}"}"""))
                },
                { listOf(call("go_back")) },
                {
                    assertTrue("url: ${site.url("options.html")}" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "x"}"""))
                },
            )

        run(model, "options.html")

        assertEquals(5, model.requests.size)
    }

    @Test
    fun `screenshot is refused once a secret was typed, since pixels can't be scrubbed`() {
        val model =
            ScriptedModel(
                { listOf(call("type_secret", """{"ref": ${it.ref("textbox", "Email address")}, "field": "email"}"""), call("screenshot")) },
                {
                    val shot = it.lastResults.last()
                    assertTrue(shot.isError, shot.text)
                    assertTrue(it.lastImages.isEmpty())
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        run(model, "login.html")

        assertEquals(2, model.requests.size)
    }
}
