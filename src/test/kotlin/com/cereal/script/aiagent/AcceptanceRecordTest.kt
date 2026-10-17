package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmTurn
import com.cereal.sdk.component.llm.LlmUsage
import com.cereal.sdk.component.llm.UserPart
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What the acceptance runs read from the test-only model client's record: the cost row and the password check. */
class AcceptanceRecordTest {
    @Test
    fun `a run's cost is priced from each response's model and usage, unknown models at 0`() {
        val responses =
            listOf(
                response(usage = LlmUsage(1000, 0, 0, 100)), // claude-opus-5-5: $0.004 in + $0.002 out
                response(usage = LlmUsage(1000, 10_000, 0, 100)), // + 10k cache reads at $0.20/M = $0.002
                response(model = "mystery-model", usage = LlmUsage(1_000_000, 0, 0, 1_000_000)),
            )

        val stats = RunStats("G2", "Once", responses, ExecutionResult.Success("done"), 0)

        assertEquals(3, stats.steps)
        assertEquals(0.014, stats.cost, 1e-9)
        assertEquals(listOf("claude-opus-5-5", "mystery-model"), stats.models)
    }

    @Test
    fun `the password check fails when a recorded request carried the password`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(COMPLETION).build())
            server.start()
            val client = OpenAiCompatibleClient(server.url("/v1").toString(), "key", "claude-opus-5-5")

            runBlocking { client.complete(LlmRequest(turns = listOf(LlmTurn.User(listOf(UserPart.Text("my password is welcome01")))))) }

            client.assertNeverSent("hunter2", "another secret")
            assertFailsWith<AssertionError> { client.assertNeverSent("welcome01", "the password") }
            assertEquals("claude-opus-5-5", client.responses.single().model)
        }
    }

    private companion object {
        const val COMPLETION =
            """{"model":"claude-opus-5-5","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
    }
}
