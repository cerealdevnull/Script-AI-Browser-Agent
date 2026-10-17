package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.Visibility
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderPresetTest {
    @Test
    fun `Server URL shows only for Custom, API key and Model swap required and optional`() {
        fun visibility(modifier: com.cereal.sdk.statemodifier.StateModifier, provider: Provider?) =
            modifier.getVisibility(form(provider))
        assertEquals(Visibility.Hidden, visibility(ServerUrlState, null))
        assertEquals(Visibility.Hidden, visibility(ServerUrlState, Provider.Gemini))
        assertEquals(Visibility.VisibleRequired, visibility(ServerUrlState, Provider.Custom))
        assertEquals(Visibility.VisibleRequired, visibility(ApiKeyState, null))
        assertEquals(Visibility.VisibleOptional, visibility(ApiKeyState, Provider.Custom))
        assertEquals(Visibility.VisibleOptional, visibility(ModelState, Provider.OpenAI))
        assertEquals(Visibility.VisibleRequired, visibility(ModelState, Provider.Custom))
    }

    @Test
    fun `a preset needs an API key`() = assertRejected(TestConfig(provider = Provider.Gemini, apiKey = null, model = null), "API key is required for Gemini")

    @Test
    fun `Custom needs a Model`() = assertRejected(TestConfig(provider = Provider.Custom, baseUrl = "http://localhost:1/v1", model = " "), "Model is required for Custom")

    @Test
    fun `Custom needs a Server URL`() = assertRejected(TestConfig(provider = Provider.Custom, model = "llama3"), "Server URL is required for Custom")

    private fun form(provider: Provider?) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue =
                if (key == "provider" && provider != null) ScriptConfigValue.EnumScriptConfigValue(provider) else ScriptConfigValue.NullScriptConfigValue
        }

    private fun assertRejected(config: TestConfig, reason: String) =
        runBlocking {
            val result = Host().run(AgentScript(tempProfiles()), config)
            assertEquals("Fix these in the task settings: $reason.", assertIs<ExecutionResult.Error>(result).message)
        }
}
