package com.cereal.script.aiagent

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Anthropic Messages API over raw OkHttp (vendor SDKs don't load in the host's ScriptClassLoader). */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ModelClient {
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
        val json =
            postJson(
                "${baseUrl.trimEnd('/')}/v1/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "anthropic-beta" to "server-side-fallback-2026-07-01"),
                body,
                sleep,
            )
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
                                        if (r.imageJpeg == null) {
                                            put("content", r.text)
                                        } else {
                                            put(
                                                "content",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "text")
                                                            put("text", r.text)
                                                        },
                                                    )
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "image")
                                                            putJsonObject("source") {
                                                                put("type", "base64")
                                                                put("media_type", "image/jpeg")
                                                                put("data", r.imageJpeg)
                                                            }
                                                        },
                                                    )
                                                },
                                            )
                                        }
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
    }
}
