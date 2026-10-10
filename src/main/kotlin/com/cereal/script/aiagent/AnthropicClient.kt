package com.cereal.script.aiagent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anthropic Messages API over raw OkHttp (vendor SDKs don't load in the host's ScriptClassLoader). */
class AnthropicClient(private val baseUrl: String, private val apiKey: String, private val model: String) : ModelClient {
    override suspend fun send(request: ModelRequest): ModelTurn {
        val body =
            buildJsonObject {
                put("model", model)
                put("max_tokens", 16000)
                putJsonObject("thinking") { put("type", "adaptive") }
                putJsonObject("output_config") { put("effort", "medium") }
                putJsonObject("tool_choice") { put("type", "auto") } // forced choice 400s on Opus 5.5
                putJsonObject("cache_control") { put("type", "ephemeral") }
                put("fallbacks", "default")
                put("system", request.system)
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("input_schema", t.schema)
                                    put("strict", true)
                                },
                            )
                        }
                    },
                )
                put("messages", JsonArray(request.messages.map(::toJson)))
            }
        val httpRequest =
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "server-side-fallback-2026-07-01")
                .post(body.toString().toRequestBody(JSON))
                .build()
        val (code, text) = HTTP.newCall(httpRequest).await()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299) {
            val message = json?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(300)
            throw ModelException("HTTP $code: $message")
        }
        json ?: throw ModelException("unreadable response: ${text.take(300)}")
        val content = json["content"]?.jsonArray ?: JsonArray(emptyList())
        val blocks = content.map { it.jsonObject }
        val usage = json["usage"] as? JsonObject
        fun usage(key: String) = (usage?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        return ModelTurn(
            toolCalls =
                blocks.filter { it.str("type") == "tool_use" }.map {
                    ToolCall(it.str("id")!!, it.str("name")!!, it["input"] as? JsonObject ?: JsonObject(emptyMap()))
                },
            stopReason = json.str("stop_reason"),
            model = json.str("model"),
            usage = Usage(usage("input_tokens"), usage("output_tokens"), usage("cache_read_input_tokens"), usage("cache_creation_input_tokens")),
            raw = content,
            text = blocks.lastOrNull { it.str("type") == "text" }?.str("text"),
        )
    }

    private fun toJson(message: Message): JsonObject =
        when (message) {
            is Message.User ->
                buildJsonObject {
                    put("role", "user")
                    put("content", message.text)
                }
            // Append-only history: the content blocks go back exactly as received, thinking blocks included.
            is Message.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    put("content", message.turn.raw ?: JsonArray(emptyList()))
                }
            is Message.ToolResults ->
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            message.results.forEach { r ->
                                add(
                                    buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", r.callId)
                                        put("content", r.text)
                                        if (r.isError) put("is_error", true)
                                    },
                                )
                            }
                        },
                    )
                }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        private val JSON = "application/json".toMediaType()
        private val HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()

        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

        /** Runs the call on OkHttp's threads; cancelling the coroutine cancels the call. */
        private suspend fun Call.await(): Pair<Int, String> =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cancel() }
                enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(ModelException("network error: ${e.message}"))

                        override fun onResponse(call: Call, response: Response) {
                            val result = runCatching { response.use { it.code to it.body.string() } }
                            result.fold(cont::resume) { cont.resumeWithException(ModelException("network error: ${it.message}")) }
                        }
                    },
                )
            }
    }
}
