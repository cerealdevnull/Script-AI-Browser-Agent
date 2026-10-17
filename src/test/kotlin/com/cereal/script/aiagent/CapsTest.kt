package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmResponse
import com.cereal.sdk.component.llm.LlmToolCall
import com.cereal.sdk.component.llm.LlmUsage
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Step and cost caps, and the cost numbers in the log and notifications. Default usage: 1000 in / 100 out = $0.006 on Opus 5.5. */
class CapsTest {
    private val site = FixtureServer()
    private val host = Host()

    @AfterTest
    fun tearDown() = site.close()

    private fun run(
        model: ScriptedModel,
        maxSteps: Int = 40,
        maxRunCost: Double = 3.0,
        maxTaskCost: Double = 10.0,
        respond: suspend (LlmRequest) -> LlmResponse = model::respond,
    ) = runBlocking {
            val config = TestConfig(startUrl = site.url(), maxSteps = maxSteps, maxRunCost = maxRunCost, maxTaskCost = maxTaskCost)
            host.llm.responder = respond
            host.run(AgentScript(tempProfiles()), config)
        }

    private val readForever: suspend (LlmRequest) -> List<LlmToolCall> = { listOf(call("read_page")) }

    @Test
    fun `logs one cost line per step and a summary line per run`() {
        val model =
            ScriptedModel(
                { listOf(call("click", """{"ref": ${it.ref("link", "Long Nose Pliers")}}""")) },
                { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) },
            )

        run(model)

        assertTrue(
            host.logs.any { it == "INFO step 1: click link \"Long Nose Pliers\" (1000 in / 100 out / 0 cache-read tokens, step \$0.0060, run total \$0.0060)" },
            host.logs.joinToString("\n"),
        )
        assertTrue(host.logs.any { it.startsWith("INFO now on ") && "product" in it }, host.logs.joinToString("\n"))
        assertTrue(host.logs.any { it.startsWith("INFO step 2: finish (") && it.endsWith("run total \$0.0120)") }, host.logs.joinToString("\n"))
        assertTrue(host.logs.any { it == "INFO run: Once, learned, 2 steps, \$0.01, In stock." }, host.logs.joinToString("\n"))
    }

    @Test
    fun `the step cap stops the run with Error and a raise-or-reword action`() {
        // An invalid-arguments turn counts as a step too.
        val model = ScriptedModel({ listOf(call("click", """{"ref": "x"}""")) }, readForever, readForever, readForever)

        val result = run(model, maxSteps = 3)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Stopped after 3 steps, the Max steps per run limit.", result.message)
        assertEquals(
            "Stopped after 3 steps, the Max steps per run limit.\n" +
                "If the agent was making progress, raise Max steps per run. Otherwise make the goal more specific.\nCost: \$0.02",
            host.notifications.single().message,
        )
        assertEquals("Stopped after 3 steps, the Max steps per run limit.", host.statuses.last())
        assertEquals(3, model.requests.size)
    }

    @Test
    fun `the run cost cap stops the run`() {
        val model = ScriptedModel(readForever, readForever, readForever)

        val result = run(model, maxRunCost = 0.01)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Stopped at \$0.01, the Max \$ per run limit.", result.message)
        assertEquals("Stopped at \$0.01, the Max \$ per run limit.\nRaise Max \$ per run to let it go further.\nCost: \$0.01", host.notifications.single().message)
    }

    @Test
    fun `the task cost cap stops the run`() {
        val model = ScriptedModel(readForever, readForever, readForever)

        val result = run(model, maxTaskCost = 0.01)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("This task has spent \$0.01, its Max \$ per task limit.", result.message)
        assertEquals("Raise Max \$ per task to keep it running.", host.notifications.single().message.lines()[1])
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `an unknown model costs unknown, logs a warning and is stopped by the step cap`() {
        val model = ScriptedModel(readForever, readForever, readForever, models = List(3) { "llama3.3:70b" })

        val result = run(model, maxSteps = 2, maxRunCost = 0.001)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Stopped after 2 steps, the Max steps per run limit.", result.message)
        assertTrue(host.notifications.single().message.endsWith("\nCost: unknown (no price for llama3.3:70b)"))
        assertEquals(1, host.logs.count { it == "WARN no price known for model llama3.3:70b: counted as \$0, only the step cap applies" })
    }

    @Test
    fun `the status shows $0 before the first response, whatever model will answer`() {
        val model = ScriptedModel({ listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) }, models = listOf("llama3.3:70b"))

        run(model)

        assertEquals("Step 1 of 40 · \$0.00 · Thinking…", host.statuses.first { it.startsWith("Step 1") })
        assertTrue(host.statuses.indexOf("Starting Chrome") < host.statuses.indexOfFirst { it.startsWith("Opening") }, host.statuses.toString())
        assertTrue(host.statuses.none { "price" in it.lowercase() || "API key" in it }, host.statuses.toString())
    }

    @Test
    fun `a fallback model is priced at its own rate`() {
        // Opus 5.5 would be $0.06; Haiku 5.5 answers: 10000 x $0.10 + 1000 x $0.50 per MTok = $0.0015.
        val model =
            ScriptedModel(
                { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) },
                usage = LlmUsage(10_000, 0, 0, 1_000),
                models = listOf("claude-haiku-5-5"),
            )

        run(model)

        assertEquals("In stock.\nCost: \$0", host.notifications.single().message)
    }

    @Test
    fun `a model with no price shows the cost as unknown, never $0`() {
        val model = ScriptedModel(readForever, { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) }, models = List(2) { "llama3.3:70b" })

        run(model)

        assertEquals("Step 2 of 40 · \$? · Thinking…", host.statuses.first { it.startsWith("Step 2") })
        assertEquals("In stock.\nCost: unknown (no price for llama3.3:70b)", host.notifications.single().message)
    }

    @Test
    fun `a run where some steps had no price shows the priced part plus unknown`() {
        val model =
            ScriptedModel(
                readForever,
                readForever,
                { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) },
                usage = LlmUsage(30_000, 0, 0, 0),
                models = listOf("claude-opus-5-5", "llama3.3:70b", "llama3.3:70b"),
            )

        run(model)

        assertEquals("Step 3 of 40 · \$0.12 + unknown · Thinking…", host.statuses.first { it.startsWith("Step 3") })
        assertEquals("In stock.\nCost: \$0.12 + unknown", host.notifications.single().message)
    }

    @Test
    fun `a turn the client rejected counts as a step at $0 and marks the cost unknown`() {
        val model = ScriptedModel(readForever, readForever, { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) })

        run(model, respond = model.rejecting(1))

        assertTrue(host.logs.any { it.startsWith("INFO step 1: ") && it.endsWith("(tokens unknown, step \$0.0000, run total \$0.0000)") }, host.logs.joinToString("\n"))
        assertEquals(1, host.logs.count { it.startsWith("WARN step 1: ") && "rejected" in it }, host.logs.joinToString("\n"))
        assertEquals("Step 3 of 40 · \$0.01 + unknown · Thinking…", host.statuses.first { it.startsWith("Step 3") })
        assertEquals("In stock.\nCost: \$0.01 + unknown", host.notifications.single().message)
    }
}
