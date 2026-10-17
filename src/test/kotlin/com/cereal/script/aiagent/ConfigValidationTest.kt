package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigValidationTest {
    @Test
    fun `missing goal`() = assertRejected(TestConfig(goal = " "), "Goal is required")

    @Test
    fun `missing start URL`() = assertRejected(TestConfig(startUrl = ""), "Start URL is required")

    @Test
    fun `non-http start URL`() = assertRejected(TestConfig(startUrl = "file:///etc/passwd"), "Start URL must be an http(s) URL")

    @Test
    fun `start URL without a host`() = assertRejected(TestConfig(startUrl = "https:/example.com/shop"), "Start URL must be an http(s) URL")

    @Test
    fun `secret labels must be unique, ignoring case and spaces`() =
        assertRejected(
            TestConfig(secrets = listOf("My Password" to Secret("a"), " my  password " to Secret("b"))),
            "Secret 2's label 'my  password' is already used by secret 1",
        )

    @Test
    fun `a secret value needs a label`() =
        assertRejected(TestConfig(secrets = listOf("email" to Secret("a"), null to Secret("b"))), "Secret 2 needs a label, e.g. password")

    @Test
    fun `a secret label needs a value`() =
        assertRejected(TestConfig(secrets = listOf("Email" to null)), "Secret 1 ('Email') needs a value")

    @Test
    fun `step cap must be positive`() = assertRejected(TestConfig(maxSteps = 0), "Max steps per run must be > 0")

    @Test
    fun `run cost cap must be positive`() = assertRejected(TestConfig(maxRunCost = 0.0), "Max $ per run must be > 0")

    @Test
    fun `task cost cap must be positive`() = assertRejected(TestConfig(maxTaskCost = -1.0), "Max $ per task must be > 0")

    @Test
    fun `repeat interval must be positive`() = assertRejected(TestConfig(runMode = RunMode.Repeat, repeatInterval = 0), "Check every must be > 0")

    @Test
    fun `a Once task ignores the hidden Check every`() = assertRejected(TestConfig(maxSteps = 0, repeatInterval = 0), "Max steps per run must be > 0")

    @Test
    fun `an old saved task lists every problem in one notification`() =
        runBlocking {
            val host = Host()
            val model = ScriptedModel()

            val result = host.run(AgentScript(tempProfiles()).also { host.llm.responder = model::respond }, TestConfig(goal = "", runMode = RunMode.Repeat, repeatInterval = 0))

            val expected = "Fix these in the task settings: Goal is required. Check every must be > 0."
            assertIs<ExecutionResult.Error>(result)
            assertEquals(expected, result.message)
            assertEquals(expected, host.statuses.last())
            assertTrue(host.notifications.single().message.startsWith("$expected\n"))
            assertTrue(model.requests.isEmpty())
        }

    @Test
    fun `a required item the host has no value for is reported as missing`() =
        runBlocking {
            // The host's config proxy throws for a non-nullable item with no value, instead of returning "".
            val unset =
                object : AgentConfiguration by TestConfig() {
                    override fun goal(): String = throw RuntimeException("No value found for configuration method 'goal'")

                    override fun startUrl(): String = throw RuntimeException("No value found for configuration method 'startUrl'")
                }

            val result = Host().run(AgentScript(tempProfiles()), unset)

            assertIs<ExecutionResult.Error>(result)
            assertEquals("Fix these in the task settings: Goal is required. Start URL is required.", result.message)
        }

    /** An invalid config ends with Error and a notification, and makes no model call. */
    private fun assertRejected(config: TestConfig, error: String) =
        runBlocking {
            val reason = "Fix these in the task settings: $error."
            val host = Host()
            val model = ScriptedModel()

            val result = host.run(AgentScript(tempProfiles()).also { host.llm.responder = model::respond }, config)

            assertIs<ExecutionResult.Error>(result)
            assertEquals(reason, result.message)
            val notification = host.notifications.single()
            assertTrue(notification.message.startsWith("$reason\n"), notification.message)
            assertTrue(model.requests.isEmpty())
        }
}
