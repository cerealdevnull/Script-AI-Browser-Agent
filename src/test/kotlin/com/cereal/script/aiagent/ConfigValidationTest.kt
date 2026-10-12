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
    fun `secret names must be unique`() =
        assertRejected(
            TestConfig(secrets = listOf("email" to Secret("a"), "email" to Secret("b"))),
            "Secret names must be unique: email",
        )

    @Test
    fun `secret names must be lowercase identifiers`() =
        assertRejected(TestConfig(secrets = listOf("E-mail" to Secret("a"))), "Secret 1 name must match [a-z][a-z0-9_]*")

    @Test
    fun `a secret value needs a name`() =
        assertRejected(TestConfig(secrets = listOf("email" to Secret("a"), null to Secret("b"))), "Secret 2 has a value but no name")

    @Test
    fun `step cap must be positive`() = assertRejected(TestConfig(maxSteps = 0), "Max steps per run must be > 0")

    @Test
    fun `run cost cap must be positive`() = assertRejected(TestConfig(maxRunCost = 0.0), "Max $ per run must be > 0")

    @Test
    fun `task cost cap must be positive`() = assertRejected(TestConfig(maxTaskCost = -1.0), "Max $ per task must be > 0")

    @Test
    fun `repeat interval must be positive`() = assertRejected(TestConfig(repeatInterval = 0), "Check every must be > 0")

    /** An invalid config ends with Error and a notification, and makes no model call. */
    private fun assertRejected(config: TestConfig, reason: String) =
        runBlocking {
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
