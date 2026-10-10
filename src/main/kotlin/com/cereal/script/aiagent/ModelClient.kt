package com.cereal.script.aiagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The script's own, provider-neutral view of a chat model with tools. Adapters map it to one provider's API. */
interface ModelClient {
    suspend fun send(request: ModelRequest): ModelTurn
}

class ModelRequest(val system: String, val tools: List<ToolDef>, val messages: List<Message>)

/** [schema] is a JSON schema object; the script validates every tool call against it. */
class ToolDef(val name: String, val description: String, val schema: JsonObject)

/** The conversation, append-only: earlier messages are never changed. */
sealed interface Message {
    class User(val text: String) : Message

    /** A model turn, echoed back to the provider as received ([ModelTurn.raw]). */
    class Assistant(val turn: ModelTurn) : Message

    class ToolResults(val results: List<ToolResult>) : Message
}

class ToolCall(val id: String, val name: String, val args: JsonObject)

class ToolResult(val callId: String, val text: String, val isError: Boolean = false)

class ModelTurn(
    val toolCalls: List<ToolCall>,
    val stopReason: String?,
    /** The model that actually answered (differs from the requested one when a fallback answered). */
    val model: String?,
    val usage: Usage,
    /** The provider's own representation of this turn (e.g. Anthropic content blocks incl. thinking). */
    val raw: JsonElement?,
    val text: String? = null,
)

class Usage(val input: Long, val output: Long, val cacheRead: Long = 0, val cacheWrite: Long = 0)

class ModelException(message: String) : Exception(message)
