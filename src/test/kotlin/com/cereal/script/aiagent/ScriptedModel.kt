package com.cereal.script.aiagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A fake model behind [ModelClient]. Each turn is a function of the request it answers, so a turn can pick a ref
 * from the snapshot it was sent. Records every request.
 */
class ScriptedModel(vararg turns: suspend (ModelRequest) -> List<ToolCall>) : ModelClient {
    private val turns = ArrayDeque(turns.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun send(request: ModelRequest): ModelTurn {
        requests += request
        val turn = turns.removeFirstOrNull() ?: error("ScriptedModel: no turn left for request ${requests.size}")
        val calls = turn(request)
        return ModelTurn(calls, "tool_use", MODEL, Usage(1000, 100), raw = null)
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}

private var callIds = 0

fun call(name: String, args: String = "{}") = ToolCall("call_${++callIds}", name, Json.parseToJsonElement(args) as JsonObject)

/** The text the model was last sent: the latest tool result, or the goal message on the first turn. */
val ModelRequest.lastText: String
    get() =
        when (val m = messages.last()) {
            is Message.User -> m.text
            is Message.ToolResults -> m.results.joinToString("\n") { it.text }
            is Message.Assistant -> error("request ends with an assistant turn")
        }

/** The `[n]` ref of the element with this role and name in the latest snapshot. */
fun ModelRequest.ref(role: String, name: String): Int =
    Regex("""\[(\d+)] ${Regex.escape(role)} "${Regex.escape(name)}"""").find(lastText)?.groupValues?.get(1)?.toInt()
        ?: error("no $role \"$name\" in snapshot:\n$lastText")
