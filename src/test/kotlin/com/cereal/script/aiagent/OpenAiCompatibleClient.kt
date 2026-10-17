package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.CerealPlatformApi
import com.cereal.sdk.component.llm.LlmComponent
import com.cereal.sdk.component.llm.LlmException
import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmResponse
import com.cereal.sdk.component.llm.LlmToolCall
import com.cereal.sdk.component.llm.LlmTurn
import com.cereal.sdk.component.llm.LlmUsage
import com.cereal.sdk.component.llm.StopReason
import com.cereal.sdk.component.llm.UserPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The test-only [LlmComponent] for the real-model runs (acceptance, smoke and field tests): Chat Completions on any
 * OpenAI-compatible server (OpenAI, Gemini's and Anthropic's OpenAI endpoints, OpenRouter, Ollama). It records every
 * exchange in [exchanges]. Unlike the desktop client it doesn't validate, retry or cache.
 */
class OpenAiCompatibleClient(private val baseUrl: String, private val apiKey: String?, private val model: String) : LlmComponent {
    class Exchange(val request: String, val code: Int, val response: String)

    val exchanges = CopyOnWriteArrayList<Exchange>()

    @OptIn(CerealPlatformApi::class)
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body =
            buildJsonObject {
                put("model", model)
                put("max_tokens", request.maxTokens)
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { t ->
                            addJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", Json.parseToJsonElement(t.inputSchemaJson))
                                }
                            }
                        }
                    },
                )
                put("messages", JsonArray(messages(request)))
            }.toString()
        val http =
            Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions").post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()
        val (code, text) =
            try {
                runInterruptible(Dispatchers.IO) { HTTP.newCall(http).execute().use { it.code to it.body.string() } }
            } catch (e: IOException) {
                throw LlmException.Unavailable("network error: ${e.message}", e)
            }
        exchanges += Exchange(body, code, text)
        val json = runCatching { Json.parseToJsonElement(text).let { it as? JsonObject ?: it.jsonArray.first().jsonObject } }.getOrNull()
        if (code !in 200..299) {
            val message = "HTTP $code: " + ((json?.get("error") as? JsonObject)?.str("message") ?: text.take(300))
            throw when {
                code == 401 || code == 403 -> LlmException.Authentication(message)
                code == 429 -> LlmException.RateLimited(message = message)
                code >= 500 -> LlmException.Unavailable(message)
                else -> LlmException.InvalidRequest(message)
            }
        }
        val choice = json?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject ?: throw LlmException.Unavailable("no choices in response: ${text.take(300)}")
        val message = choice["message"] as? JsonObject ?: throw LlmException.Unavailable("no message in response")
        val usage = json["usage"] as? JsonObject
        fun JsonObject?.long(key: String) = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        val prompt = usage.long("prompt_tokens")
        val details = usage?.get("prompt_tokens_details") as? JsonObject
        val cached = details.long("cached_tokens")
        val written = details.long("cache_write_tokens")
        // Gemini leaves thinking out of completion_tokens, so the real output is total − prompt.
        val output = maxOf(usage.long("total_tokens") - prompt, usage.long("completion_tokens"))
        val calls =
            message["tool_calls"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
                val function = it["function"]!!.jsonObject
                LlmToolCall(it.str("id")!!, function.str("name")!!, function.str("arguments").orEmpty().ifBlank { "{}" })
            }
        val stopReason =
            when {
                message.str("refusal") != null -> StopReason.Refusal
                choice.str("finish_reason") == "length" -> StopReason.MaxTokens
                calls.isNotEmpty() -> StopReason.ToolUse
                else -> StopReason.EndTurn
            }
        return LlmResponse(
            message.str("content") ?: message.str("refusal").orEmpty(),
            calls,
            null,
            stopReason,
            LlmUsage((prompt - cached - written).toInt(), cached.toInt(), written.toInt(), output.toInt()),
            json.str("model") ?: model,
            // The assistant message goes back as received (Gemini thought signatures ride in it).
            message.toString(),
        )
    }

    @OptIn(CerealPlatformApi::class)
    private fun messages(request: LlmRequest): List<JsonObject> =
        listOfNotNull(request.system?.let { buildJsonObject { put("role", "system"); put("content", it) } }) +
            request.turns.flatMap { turn ->
                when (turn) {
                    is LlmTurn.Assistant -> listOf(turn.providerPayload?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject { put("role", "assistant"); put("content", turn.text) })
                    is LlmTurn.User -> {
                        val results = turn.parts.filterIsInstance<UserPart.ToolResult>()
                        val rest = turn.parts.filterNot { it is UserPart.ToolResult }
                        results.map { r ->
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", r.toolCallId)
                                put("content", if (r.isError) "ERROR: ${r.text}" else r.text)
                            }
                        } + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let(::user))
                    }
                }
            }

    // Tool messages carry text only, so the screenshots beside them follow as a user message.
    private fun user(parts: List<UserPart>) =
        buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                parts.forEach { p ->
                    addJsonObject {
                        when (p) {
                            is UserPart.Text -> {
                                put("type", "text")
                                put("text", p.text)
                            }
                            is UserPart.Image -> {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:${p.mediaType.mimeType};base64,${Base64.getEncoder().encodeToString(p.bytes)}") }
                            }
                            is UserPart.ToolResult -> error("tool results go in tool messages")
                        }
                    }
                }
            }
        }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        val HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()
    }
}
