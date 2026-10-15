package com.cereal.script.aiagent

import com.cereal.sdk.FieldOption
import com.cereal.sdk.FieldOptions
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Model field's dropdown. The host keeps the field free-text, so a failure here never blocks a typed id.
 * The jar build restores this class's compiler original (the host needs its Kotlin metadata for objectInstance), so it
 * may reference only names the shrinking keeps: [loadModelOptions].
 */
object ModelOptions : FieldOptions {
    override val dependsOn = setOf("provider", "apiKey", "baseUrl")

    override suspend fun load(config: ScriptConfig): List<FieldOption> = loadModelOptions(config)
}

internal suspend fun loadModelOptions(config: ScriptConfig): List<FieldOption> {
    val provider = config.provider()
    val apiKey = (config.valueForKey("apiKey") as? ScriptConfigValue.SecretScriptConfigValue)?.value?.reveal()?.trim()?.ifEmpty { null }
    if (provider != Provider.Custom && apiKey == null) throw Exception("Enter your API key to list models")
    // A preset's own server only: the key must not go elsewhere.
    val baseUrl = provider.baseUrl ?: config.string("baseUrl")?.trim()?.ifEmpty { null } ?: throw Exception("Enter your Server URL to list models")
    return listModels(provider, apiKey, baseUrl)
}

// ponytail: id-based guess at non-chat models (lists carry no capability flags except OpenRouter's); extend as providers add kinds.
private val NOT_CHAT = Regex("embed|tts|whisper|transcribe|dall-e|image|imagen|veo|moderation|realtime|audio|aqa")

/** The pre-flight's list endpoints: Anthropic's `GET /v1/models`, everyone else's `GET /models`. Usable chat models, recommended first. */
internal suspend fun listModels(provider: Provider, apiKey: String?, baseUrl: String): List<FieldOption> {
    val base = baseUrl.trimEnd('/')
    val json =
        try {
            if (provider == Provider.Anthropic) {
                getJson("$base/v1/models?limit=1000", mapOf("x-api-key" to apiKey.orEmpty(), "anthropic-version" to "2023-06-01")) { delay(it) }
            } else {
                getJson("$base/models", if (apiKey == null) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")) { delay(it) }
            }
        } catch (e: ModelException) {
            if (e.status == 401 || e.status == 403) throw Exception(keyRejected(provider))
            throw Exception("Couldn't list ${who(provider)}'s models (${e.status?.let { "HTTP $it" } ?: "network error"}).")
        }
    val usable =
        (json["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { m ->
            val params = m["supported_parameters"] as? JsonArray // OpenRouter's
            params == null || params.any { (it as? JsonPrimitive)?.content == "tools" }
        }.mapNotNull { m ->
            m.str("id")?.removePrefix("models/")?.takeUnless { NOT_CHAT.containsMatchIn(it) }?.let { FieldOption(it, m.str("display_name") ?: m.str("name")) }
        }
    val (recommended, rest) = usable.partition { it.id == provider.recommendedModel }
    return recommended.map { it.copy(label = listOfNotNull(it.label, "(recommended)").joinToString(" ")) } + rest
}
