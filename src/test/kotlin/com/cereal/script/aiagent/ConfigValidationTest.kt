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
    fun `missing API key`() = assertRejected(TestConfig(apiKey = null), "API key is required for Anthropic")

    @Test
    fun `secret labels must be unique, ignoring case and spaces around them`() =
        assertRejected(
            TestConfig(secrets = listOf("Email" to Secret("a"), " email " to Secret("b"))),
            "The label 'email' is already used by another secret",
        )

    @Test
    fun `a secret value needs a label`() =
        assertRejected(TestConfig(secrets = listOf("email" to Secret("a"), null to Secret("b"))), "Give this value a label, e.g. password")

    @Test
    fun `a secret label needs a value`() =
        assertRejected(TestConfig(secrets = listOf("Email" to null)), "Enter the value for 'Email'")

    @Test
    fun `step cap must be positive`() = assertRejected(TestConfig(maxSteps = 0), "Max steps per run must be > 0")

    @Test
    fun `run cost cap must be positive`() = assertRejected(TestConfig(maxRunCost = 0.0), "Max $ per run must be > 0")

    @Test
    fun `task cost cap must be positive`() = assertRejected(TestConfig(maxTaskCost = -1.0), "Max $ per task must be > 0")

    @Test
    fun `repeat interval must be positive`() = assertRejected(TestConfig(repeatInterval = 0), "Check every must be > 0")

    @Test
    fun `an old saved task lists every problem in one notification`() =
        runBlocking {
            val host = Host()
            val model = ScriptedModel()

            val result = host.run(AiAgentScript({ model }, tempProfiles()), TestConfig(apiKey = null, repeatInterval = 0))

            val expected = "Fix these in the task settings: API key is required for Anthropic. Check every must be > 0."
            assertIs<ExecutionResult.Error>(result)
            assertEquals(expected, result.message)
            assertEquals(expected, host.statuses.last())
            assertTrue(host.notifications.single().message.startsWith("$expected\n"))
            assertTrue(model.requests.isEmpty())
        }

    /** An invalid config ends with Error and a notification, and makes no model call. */
    private fun assertRejected(config: TestConfig, error: String) =
        runBlocking {
            val reason = "Fix these in the task settings: $error."
            val host = Host()
            val model = ScriptedModel()

            val result = host.run(AiAgentScript({ model }, tempProfiles()), config)

            assertIs<ExecutionResult.Error>(result)
            assertEquals(reason, result.message)
            val notification = host.notifications.single()
            assertTrue(notification.message.startsWith("$reason\n"), notification.message)
            assertTrue(model.requests.isEmpty())
        }
}
