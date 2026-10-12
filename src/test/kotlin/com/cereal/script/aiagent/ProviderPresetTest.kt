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
    fun `a fresh task on Anthropic with an empty Model runs on Claude Sonnet 5_5 at Anthropic, ignoring a leftover Server URL`() {
        val client = assertIs<AnthropicClient>(modelClient(TestConfig(model = null, baseUrl = "http://localhost:11434/v1")))
        assertEquals("https://api.anthropic.com", client.baseUrl)
        assertEquals("claude-sonnet-5-5", client.model)
    }

    @Test
    fun `Gemini and OpenAI presets use their own server and recommended model`() {
        val gemini = assertIs<OpenAiCompatibleClient>(modelClient(TestConfig(provider = Provider.Gemini, model = " ", baseUrl = "http://x")))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.baseUrl)
        assertEquals("gemini-3.8-flash", gemini.model)
        val openAi = assertIs<OpenAiCompatibleClient>(modelClient(TestConfig(provider = Provider.OpenAI, model = null)))
        assertEquals("https://api.openai.com/v1", openAi.baseUrl)
        assertEquals("gpt-5.6-luna", openAi.model)
    }

    @Test
    fun `a chosen Model overrides the recommended one`() =
        assertEquals("gpt-5.6-sol", assertIs<OpenAiCompatibleClient>(modelClient(TestConfig(provider = Provider.OpenAI, model = "gpt-5.6-sol"))).model)

    @Test
    fun `Custom uses the Server URL and the Model as given`() {
        val client = assertIs<OpenAiCompatibleClient>(modelClient(TestConfig(provider = Provider.Custom, baseUrl = " http://localhost:1234/v1 ", model = "llama3")))
        assertEquals("http://localhost:1234/v1", client.baseUrl)
        assertEquals("llama3", client.model)
    }

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
    fun `a preset needs an API key`() = assertRejected(TestConfig(provider = Provider.Gemini, apiKey = null), "API key is required for Gemini")

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
            val result = Host().run(AiAgentScript({ ScriptedModel() }, tempProfiles()), config)
            assertEquals(reason, assertIs<ExecutionResult.Error>(result).message)
        }
}
