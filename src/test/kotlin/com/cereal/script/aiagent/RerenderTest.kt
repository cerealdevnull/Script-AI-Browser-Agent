package com.cereal.script.aiagent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A click on an element the site re-rendered after the snapshot finds it again by its role and name. */
class RerenderTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel, path: String) =
        runBlocking { host.run(AgentScript({ model }, tempProfiles()), TestConfig(goal = "Add the hammer to the cart", startUrl = site.url(path))) }

    /** Clicks Add to cart once the page has replaced it (it does so 2 s after load). */
    private val clickAfterRerender: suspend (ModelRequest) -> List<ToolCall> = {
        val ref = it.ref("button", "Add to cart")
        delay(3000)
        listOf(call("click", """{"ref": $ref}"""))
    }

    @Test
    fun `a click on a re-rendered button succeeds in the same step`() {
        val model =
            ScriptedModel(
                clickAfterRerender,
                {
                    assertFalse(it.lastResults.single().isError, it.lastText)
                    assertTrue("Cart: 1 item" in it.lastText, it.lastText)
                    listOf(call("finish", """{"goal_met": true, "summary": "Added."}"""))
                },
            )

        assertEquals("Added.", run(model, "rerender.html")?.message)
        assertEquals(2, model.requests.size)
        assertFalse(host.logs.any { "detached" in it.lowercase() }, host.logs.joinToString("\n"))
    }

    @Test
    fun `two re-rendered buttons with the same name give the model the error`() {
        val model =
            ScriptedModel(
                clickAfterRerender,
                {
                    val result = it.lastResults.single()
                    assertTrue(result.isError && "detached" in result.text.lowercase(), result.text)
                    listOf(call("fail", """{"reason": "stuck"}"""))
                },
            )

        run(model, "rerender.html?copies=2")
        assertFalse("Cart: 1 item" in model.requests.last().text())
    }
}
