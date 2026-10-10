package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                AiAgentScript({ model }, tempProfiles()),
                TestConfig(goal = "x", startUrl = site.url(startPath), secrets = listOf("email" to Secret("jane@example.com"))),
            )
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
                    assertNotNull(shot.imageJpeg)
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
                    assertNull(shot.imageJpeg)
                    listOf(call("fail", """{"reason": "x"}"""))
                },
            )

        run(model, "login.html")

        assertEquals(2, model.requests.size)
    }
}
