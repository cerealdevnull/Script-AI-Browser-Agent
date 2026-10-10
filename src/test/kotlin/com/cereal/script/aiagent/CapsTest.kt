package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
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

    private fun run(model: ScriptedModel, maxSteps: Int = 40, maxRunCost: Double = 3.0, maxTaskCost: Double = 10.0) =
        runBlocking {
            val config = TestConfig(startUrl = site.url(), maxSteps = maxSteps, maxRunCost = maxRunCost, maxTaskCost = maxTaskCost)
            host.run(AiAgentScript({ model }, tempProfiles()), config)
        }

    private val readForever: suspend (ModelRequest) -> List<ToolCall> = { listOf(call("read_page")) }

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
        assertTrue(host.logs.any { it.startsWith("INFO step 2: finish (") && it.endsWith("run total \$0.0120)") }, host.logs.joinToString("\n"))
        assertTrue(host.logs.any { it == "INFO run: Once, learned, 2 steps, \$0.01, Goal met" }, host.logs.joinToString("\n"))
    }

    @Test
    fun `the step cap stops the run with Error and a notification`() {
        // An invalid-arguments turn counts as a step too.
        val model = ScriptedModel({ listOf(call("click", """{"ref": "x"}""")) }, readForever, readForever)

        val result = run(model, maxSteps = 2)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Step/cost limit reached: max steps per run (2)", result.message)
        assertEquals("Stopped: Step/cost limit reached: max steps per run (2)\n2 steps, \$0.01", host.notifications.single().message)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `the run cost cap stops the run`() {
        val model = ScriptedModel(readForever, readForever, readForever)

        val result = run(model, maxRunCost = 0.01)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Step/cost limit reached: max \$ per run (\$0.01)", result.message)
        assertEquals("Stopped: Step/cost limit reached: max \$ per run (\$0.01)\n2 steps, \$0.01", host.notifications.single().message)
    }

    @Test
    fun `the task cost cap stops the run`() {
        val model = ScriptedModel(readForever, readForever, readForever)

        val result = run(model, maxTaskCost = 0.01)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Step/cost limit reached: max \$ per task (\$0.01)", result.message)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `an unknown model costs nothing, logs a warning and is stopped by the step cap`() {
        val model = ScriptedModel(readForever, readForever, readForever, models = List(3) { "llama3.3:70b" })

        val result = run(model, maxSteps = 2, maxRunCost = 0.001)

        assertIs<ExecutionResult.Error>(result)
        assertEquals("Step/cost limit reached: max steps per run (2)", result.message)
        assertTrue(host.notifications.single().message.endsWith("\n2 steps, \$0.00"))
        assertEquals(1, host.logs.count { it == "WARN no price known for model llama3.3:70b: counted as \$0, only the step cap applies" })
    }

    @Test
    fun `a fallback model is priced at its own rate`() {
        // Opus 5.5 would be $0.06; Haiku 5.5 answers: 10000 x $0.10 + 1000 x $0.50 per MTok = $0.0015.
        val model =
            ScriptedModel(
                { listOf(call("finish", """{"goal_met": true, "summary": "In stock."}""")) },
                usage = Usage(10_000, 1_000),
                models = listOf("claude-haiku-5-5"),
            )

        run(model)

        assertEquals("Goal met\nIn stock.\n1 step, \$0.00", host.notifications.single().message)
    }
}
