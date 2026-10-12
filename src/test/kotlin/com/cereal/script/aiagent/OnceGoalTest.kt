package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real Chrome against local fixture pages, with a scripted model. */
class OnceGoalTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel, goal: String = "Tell me whether Long Nose Pliers is in stock") =
        runBlocking { host.run(AiAgentScript({ model }, tempProfiles()), TestConfig(goal = goal, startUrl = site.url())) }

    @Test
    fun `reaches the goal and notifies the verdict and summary`() {
        val model =
            ScriptedModel(
                { call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""").let(::listOf) },
                {
                    assertTrue("In stock" in it.lastText, "the click result carries a fresh snapshot")
                    listOf(call("finish", """{"goal_met": true, "summary": "Long Nose Pliers is in stock at $14.15."}"""))
                },
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        val notification = host.notifications.single()
        assertEquals("AI agent: Tell me whether Long Nose Pliers is in stock", notification.title)
        assertEquals("Goal met\nLong Nose Pliers is in stock at $14.15.\nCost: $0.01", notification.message)
        assertEquals(host.statuses.last(), notification.message.lines().first(), "the headline is the final status line")
    }

    @Test
    fun `a goal over 60 characters is cut in the title with an ellipsis`() {
        val goal = "Tell me whether Long Nose Pliers is in stock, and at what price today"
        run(ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "Yes."}""")) }), goal)

        assertEquals("AI agent: Tell me whether Long Nose Pliers is in stock, and at what pr…", host.notifications.single().title)
    }

    @Test
    fun `fail from the model ends with Error and a notification carrying the reason`() {
        val model = ScriptedModel({ listOf(call("fail", """{"reason": "The product page shows no stock information."}""")) })

        val result = run(model)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("The product page shows no stock information.", result.message)
        assertEquals("The product page shows no stock information.\nCost: $0.01", host.notifications.single().message)
        assertEquals("The product page shows no stock information.", host.statuses.last())
    }

    @Test
    fun `invalid tool arguments come back to the model as an error result and the run continues`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": "the pliers link"}""")) },
                {
                    val result = (it.messages.last() as Message.ToolResults).results.single()
                    assertTrue(result.isError)
                    assertEquals("invalid arguments: argument 'ref' must be integer, got \"the pliers link\"", result.text)
                    listOf(call("finish", """{"goal_met": false, "summary": "Not checked."}"""))
                },
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(2, model.requests.size)
    }
}
