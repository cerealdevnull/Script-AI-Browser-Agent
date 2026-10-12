package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Text refs and Readouts: values are read from the page, verbatim, never taken from the model. */
class ReadoutTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(model: ScriptedModel) =
        runBlocking {
            host.run(
                AiAgentScript({ model }, tempProfiles()),
                TestConfig(goal = "Tell me the latest invoice's number, date and total", startUrl = site.url("invoices.html")),
            )
        }

    private fun readouts(request: ModelRequest, number: String = "INV-2026000042") =
        """[{"label": "Invoice number", "ref": "${request.textRef("cell", "INV-2026000042")}", "value": "$number"},
            {"label": "Date", "ref": "${request.textRef("cell", "2026-10-01")}", "value": "2026-10-01"},
            {"label": "Total", "ref": "${request.textRef("cell", "$27.30")}", "value": "$27.30"}]"""

    @Test
    fun `a Once run notifies one label-value line per Readout, read from the page`() {
        val model =
            ScriptedModel({
                listOf(call("finish", """{"goal_met": true, "summary": "Found the latest invoice.", "readouts": ${readouts(it)}}"""))
            })

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(
            "Found the latest invoice.\nInvoice number: INV-2026000042\nDate: 2026-10-01\nTotal: $27.30\nCost: $0.01",
            host.notifications.single().message,
        )
    }

    @Test
    fun `a Once run with Readouts is cached, and the next run replays it with zero model calls`() {
        run(ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": ${readouts(it)}}""")) }))
        val second = ScriptedModel()

        assertIs<ExecutionResult.Success>(run(second))

        assertTrue(second.requests.isEmpty())
        assertEquals("Goal met\nInvoice number: INV-2026000042\nDate: 2026-10-01\nTotal: $27.30\nCost: $0 (reused what it learned last time)", host.notifications.last().message)
    }

    @Test
    fun `an action tool given a text ref returns a tool error`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": "${it.textRef("cell", "INV-2026000042")}"}""")) },
                {
                    val result = (it.messages.last() as Message.ToolResults).results.single()
                    assertTrue(result.isError)
                    assertTrue("read-only" in result.text, result.text)
                    listOf(call("finish", """{"goal_met": false, "summary": "Nothing to click.", "readouts": null}"""))
                },
            )

        assertIs<ExecutionResult.Success>(run(model))
    }

    @Test
    fun `a Readout whose text does not match the reported value is rejected and retried at most twice`() {
        // Refs stay valid until the next snapshot, so every retry reuses those from the first request.
        var wrongReadouts = ""
        val wrong = { r: ModelRequest ->
            if (wrongReadouts.isEmpty()) wrongReadouts = readouts(r, number = "INV-1")
            listOf(call("finish", """{"goal_met": true, "summary": "Found it.", "readouts": $wrongReadouts}"""))
        }
        val model =
            ScriptedModel(
                wrong,
                {
                    val result = (it.messages.last() as Message.ToolResults).results.single()
                    assertTrue(result.isError)
                    assertTrue("INV-2026000042" in result.text && "INV-1" in result.text, result.text)
                    wrong(it)
                },
                wrong,
            )

        val result = run(model)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(3, model.requests.size)
        // The unverified value is never reported; the verified ones are.
        assertEquals("Found it.\nDate: 2026-10-01\nTotal: $27.30\nCost: $0.02", host.notifications.single().message)
        assertTrue(host.logs.any { "Readout" in it && "INV-1" in it }, host.logs.joinToString("\n"))
    }
}
