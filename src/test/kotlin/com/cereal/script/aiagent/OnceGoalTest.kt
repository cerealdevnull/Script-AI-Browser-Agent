package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.component.notification.telegram.model.TelegramParseMode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Chrome against local fixture pages, with a scripted model. */
class OnceGoalTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel, goal: String = "Tell me whether Long Nose Pliers is in stock") =
        runBlocking { host.run(AgentScript(tempProfiles()).also { host.llm.responder = model::respond }, TestConfig(goal = goal, startUrl = site.url())) }

    @Test
    fun `reaches the goal and the summary's first sentence is the headline`() {
        val model =
            ScriptedModel(
                { call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""").let(::listOf) },
                {
                    assertTrue("In stock" in it.lastText, "the click result carries a fresh snapshot")
                    listOf(call("finish", """{"goal_met": true, "summary": "Long Nose Pliers is in stock. It costs $14.15."}"""))
                },
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        val notification = host.notifications.single()
        assertEquals("AI agent: Tell me whether Long Nose Pliers is in stock", notification.title)
        assertEquals("Long Nose Pliers is in stock.\nIt costs $14.15.\nCost: $0.01", notification.message)
        assertEquals(host.statuses.last(), notification.message.lines().first(), "the headline is the final status line")
    }

    @Test
    fun `a goal not met opens with the model's answer, not Goal not met`() {
        val result = run(ScriptedModel({ listOf(call("finish", """{"goal_met": false, "summary": "Long Nose Pliers is out of stock."}""")) }))

        assertIs<ExecutionResult.Success>(result)
        assertEquals("Long Nose Pliers is out of stock.", result.message)
        assertEquals("Long Nose Pliers is out of stock.\nCost: $0.01", host.notifications.single().message)
        assertEquals("Long Nose Pliers is out of stock.", host.statuses.last())
    }

    @Test
    fun `when finish retries run out after a rejected flip to true, the run ends with the earlier not met`() {
        var ref = 0
        val exists = { r: LlmRequest ->
            if (ref == 0) ref = r.ref("link", "Long Nose Pliers")
            """{"ref": "$ref", "condition": "exists", "expected": null}"""
        }
        val flip = { r: LlmRequest -> listOf(call("finish", """{"goal_met": true, "summary": "Long Nose Pliers is in stock.", "check": ${exists(r)}}""")) }
        val model =
            ScriptedModel(
                // The Check holds now, so this not-met finish is rejected; the model then answers met instead.
                { listOf(call("finish", """{"goal_met": false, "summary": "Long Nose Pliers is out of stock.", "check": ${exists(it)}}""")) },
                flip,
                flip,
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("Long Nose Pliers is out of stock.", result.message)
        assertTrue(host.notifications.none { "in stock." in it.message }, host.notifications.joinToString { it.message })
    }

    @Test
    fun `the Once prompt asks for a one-sentence answer first`() {
        val model = ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "Yes."}""")) })
        run(model)
        assertTrue("Start summary with a one-sentence answer" in model.requests.first().text(), model.requests.first().text())
    }

    @Test
    fun `a goal over 60 characters is cut in the title with an ellipsis`() {
        val goal = "Tell me whether Long Nose Pliers is in stock, and at what price today"
        run(ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "Yes."}""")) }), goal)

        assertEquals("AI agent: Tell me whether Long Nose Pliers is in stock, and at what pr…", host.notifications.single().title)
    }

    @Test
    fun `Telegram and Discord get the result rich, escaped, with the page link, so a URL with an underscore is not dropped`() {
        run(ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "In stock <b>&</b>. See https://shop.test/long_nose_pliers"}""")) }))

        val notification = host.notifications.single()
        val telegram = assertNotNull(notification.telegramMessage)
        assertEquals(TelegramParseMode.HTML, telegram.parseMode)
        assertTrue(telegram.text.startsWith("✅ <b>In stock &lt;b&gt;&amp;&lt;/b&gt;.</b>\n"), telegram.text)
        assertTrue("<i>See https://shop.test/long_nose_pliers</i>" in telegram.text, telegram.text)
        assertTrue("<a href=\"${site.url()}\">Open page</a> · Cost: $0.01" in telegram.text, telegram.text)
        val embed = notification.discordMessage!!.embeds!!.single()
        assertEquals(site.url(), embed.url)
        assertEquals("@here", notification.discordMessage!!.content)
    }

    @Test
    fun `a failure before Chrome starts has no page link`() {
        runBlocking { host.run(AgentScript(tempProfiles(), launchBrowser = { _, _ -> error("no Chrome") }), TestConfig(startUrl = site.url())) }

        val notification = host.notifications.single()
        assertEquals("15158332", notification.discordMessage!!.embeds!!.single().color)
        assertNull(notification.discordMessage!!.embeds!!.single().url)
        assertTrue("Open page" !in notification.telegramMessage!!.text)
    }

    @Test
    fun `fail from the model ends with Error and a gave-up headline carrying the reason, with no action line`() {
        val model = ScriptedModel({ listOf(call("fail", """{"reason": "The product page shows no stock information."}""")) })

        val result = run(model)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("The agent gave up: The product page shows no stock information.", result.message)
        assertEquals("The agent gave up: The product page shows no stock information.\nCost: $0.01", host.notifications.single().message)
        assertEquals("The agent gave up: The product page shows no stock information.", host.statuses.last())
    }

    @Test
    fun `invalid tool arguments come back to the model as an error result and the run continues`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": "the pliers link"}""")) },
                {
                    val result = it.lastResults.single()
                    assertTrue(result.isError)
                    assertEquals("invalid arguments: argument 'ref' must be integer, got \"the pliers link\"", result.text)
                    listOf(call("finish", """{"goal_met": false, "summary": "Not checked."}"""))
                },
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `a change Check is refused in Once mode`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) },
                {
                    val check = """{"ref": "${it.textRef("StaticText", "In stock")}", "condition": "changed", "expected": null, "decimal": null}"""
                    listOf(call("finish", """{"goal_met": false, "summary": "In stock.", "readouts": null, "check": $check}"""))
                },
                {
                    val error = it.lastResults.single().text
                    assertTrue("Check: changed/decreased need Repeat mode" in error, error)
                    listOf(call("finish", """{"goal_met": false, "summary": "In stock.", "readouts": null, "check": null}"""))
                },
            )

        assertIs<ExecutionResult.Success>(run(model))
        assertEquals(3, model.requests.size)
    }
}
