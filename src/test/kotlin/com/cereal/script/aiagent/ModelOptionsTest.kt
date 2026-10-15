package com.cereal.script.aiagent

import com.cereal.sdk.FieldOption
import com.cereal.sdk.models.Secret
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The Model field's dropdown: the provider's usable models, recommended first, from the pre-flight's list endpoints. */
class ModelOptionsTest {
    private val api = MockWebServer().apply { start() }
    private val base = api.url("/").toString().removeSuffix("/")

    @AfterTest
    fun tearDown() = api.close()

    private fun reply(code: Int, body: String = "{}") = api.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `OpenAI lists only its chat models, the recommended one first`() =
        runBlocking {
            reply(
                200,
                """{"data":[{"id":"gpt-5.5"},{"id":"text-embedding-3-large"},{"id":"gpt-5.6-luna"},{"id":"whisper-1"},
                   {"id":"tts-1"},{"id":"dall-e-3"},{"id":"gpt-image-1"},{"id":"omni-moderation-latest"},{"id":"gpt-4o-realtime-preview"},
                   {"id":"gpt-4o-transcribe"},{"id":"o3"}]}""",
            )

            val options = listModels(Provider.OpenAI, "sk-test", base)

            assertEquals(listOf(FieldOption("gpt-5.6-luna", "(recommended)"), FieldOption("gpt-5.5"), FieldOption("o3")), options)
            val request = api.takeRequest()
            assertEquals("/models", request.url.encodedPath)
            assertEquals("Bearer sk-test", request.headers["Authorization"])
        }

    @Test
    fun `Gemini's models arrive without their models prefix`() =
        runBlocking {
            reply(200, """{"data":[{"id":"models/gemini-3.5-pro"},{"id":"models/gemini-embedding-001"},{"id":"models/gemini-3.8-flash"},{"id":"models/imagen-4.0-generate-001"},{"id":"models/veo-3.0-generate-001"}]}""")

            assertEquals(
                listOf(FieldOption("gemini-3.8-flash", "(recommended)"), FieldOption("gemini-3.5-pro")),
                listModels(Provider.Gemini, "k", base),
            )
        }

    @Test
    fun `OpenRouter drops models without tools and keeps its names`() =
        runBlocking {
            reply(
                200,
                """{"data":[{"id":"x/no-tools","name":"X: No tools","supported_parameters":["temperature"]},
                   {"id":"openai/gpt-5.6-luna","name":"OpenAI: GPT-5.6 Luna","supported_parameters":["tools"]},
                   {"id":"anthropic/claude-sonnet-5.5","name":"Anthropic: Claude Sonnet 5.5","supported_parameters":["tools","temperature"]}]}""",
            )

            assertEquals(
                listOf(
                    FieldOption("anthropic/claude-sonnet-5.5", "Anthropic: Claude Sonnet 5.5 (recommended)"),
                    FieldOption("openai/gpt-5.6-luna", "OpenAI: GPT-5.6 Luna"),
                ),
                listModels(Provider.OpenRouter, "k", base),
            )
        }

    @Test
    fun `Anthropic lists its models with display names`() =
        runBlocking {
            reply(
                200,
                """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
                   {"type":"model","id":"claude-sonnet-5-5","display_name":"Claude Sonnet 5.5"}],"has_more":false}""",
            )

            assertEquals(
                listOf(FieldOption("claude-sonnet-5-5", "Claude Sonnet 5.5 (recommended)"), FieldOption("claude-opus-5-5", "Claude Opus 5.5")),
                listModels(Provider.Anthropic, "sk-ant", base),
            )
            val request = api.takeRequest()
            assertEquals("/v1/models", request.url.encodedPath)
            assertEquals("sk-ant", request.headers["x-api-key"])
        }

    @Test
    fun `a rejected key fails with the run's rejected-key wording`() =
        runBlocking {
            reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
            assertEquals(
                "Anthropic rejected your API key. Copy it again from https://console.anthropic.com/settings/keys into API key.",
                assertFailsWith<Exception> { listModels(Provider.Anthropic, "sk-wrong", base) }.message,
            )
            reply(403)
            assertEquals(
                "OpenAI rejected your API key. Copy it again from https://platform.openai.com/api-keys into API key.",
                assertFailsWith<Exception> { listModels(Provider.OpenAI, "sk-wrong", base) }.message,
            )
        }

    @Test
    fun `a preset without a key asks for one before calling anyone`() =
        runBlocking {
            val error = assertFailsWith<Exception> { ModelOptions.load(form(Provider.OpenAI, apiKey = " ")) }
            assertEquals("Enter your API key to list models", error.message)
            assertEquals(0, api.requestCount)
        }

    @Test
    fun `Custom lists its server's models, without a key or a recommendation`() =
        runBlocking {
            reply(200, """{"data":[{"id":"llama3"},{"id":"nomic-embed-text"},{"id":"qwen3"}]}""")

            assertEquals(listOf(FieldOption("llama3"), FieldOption("qwen3")), ModelOptions.load(form(Provider.Custom, baseUrl = "$base/v1")))
            val request = api.takeRequest()
            assertEquals("/v1/models", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun `a server that never answers is cancelled by the form's timeout`() {
        FixtureServer().use { hanging ->
            for (provider in listOf(Provider.Anthropic, Provider.OpenAI)) {
                assertFailsWith<TimeoutCancellationException>(provider.name) {
                    runBlocking { withTimeout(1_000) { listModels(provider, "k", hanging.url("hang")) } }
                }
            }
        }
    }

    @Test
    fun `Model shows the provider's recommended model as its placeholder`() {
        assertEquals("claude-sonnet-5-5", ModelState.getPlaceholder(form(Provider.Anthropic)))
        assertEquals("anthropic/claude-sonnet-5.5", ModelState.getPlaceholder(form(Provider.OpenRouter)))
        assertNull(ModelState.getPlaceholder(form(Provider.Custom)))
    }

    private fun form(provider: Provider, apiKey: String? = null, baseUrl: String? = null) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue =
                when (key) {
                    "provider" -> ScriptConfigValue.EnumScriptConfigValue(provider)
                    "apiKey" -> apiKey?.let { ScriptConfigValue.SecretScriptConfigValue(Secret(it)) }
                    "baseUrl" -> baseUrl?.let(ScriptConfigValue::StringScriptConfigValue)
                    else -> null
                } ?: ScriptConfigValue.NullScriptConfigValue
        }
}
