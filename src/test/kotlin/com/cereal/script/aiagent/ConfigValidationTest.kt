package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
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

    /** An invalid config ends with Error and a notification, and makes no model call. */
    private fun assertRejected(config: TestConfig, reason: String) =
        runBlocking {
            val host = Host()
            val model = ScriptedModel()

            val result = host.run(AiAgentScript({ model }, tempProfiles()), config)

            assertIs<ExecutionResult.Error>(result)
            assertEquals(reason, result.message)
            val notification = host.notifications.single()
            assertTrue(notification.message.startsWith("Failed: $reason"), notification.message)
            assertTrue(model.requests.isEmpty())
        }
}
