package com.cereal.script.aiagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The script's own, provider-neutral view of a chat model with tools. Adapters map it to one provider's API. */
interface ModelClient {
    suspend fun send(request: ModelRequest): ModelTurn

    /**
     * A free lookup of the model before Chrome starts: null when it looks fine or the server can't tell. A transient
     * failure (network, 429, 5xx) throws a transient [ModelException].
     */
    suspend fun check(): Preflight? = null
}

enum class Preflight { BadKey, UnknownModel, NoTools }

/** [onRetry]: called with the wait in ms before each retry of a busy (429/5xx) or unreachable provider. */
class ModelRequest(val system: String, val tools: List<ToolDef>, val messages: List<Message>, val onRetry: suspend (Long) -> Unit = {})

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

/** [imageJpeg]: a base64 JPEG attached to the result (the screenshot fallback). */
class ToolResult(val callId: String, val text: String, val isError: Boolean = false, val imageJpeg: String? = null)

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

/**
 * A model API or network failure; [message] is the user-facing reason. [transient]: a 429, 5xx or network error that
 * outlasted its retries, so a later attempt may work. Non-transient: rejected key/model or another 4xx. [status]: the
 * HTTP status, null for a network error.
 */
class ModelException(message: String, val transient: Boolean = false, val status: Int? = null) : Exception(message)
