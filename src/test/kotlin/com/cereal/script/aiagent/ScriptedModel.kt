package com.cereal.script.aiagent

import com.cereal.sdk.component.llm.CerealPlatformApi
import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmResponse
import com.cereal.sdk.component.llm.LlmToolCall
import com.cereal.sdk.component.llm.LlmTurn
import com.cereal.sdk.component.llm.LlmUsage
import com.cereal.sdk.component.llm.StopReason
import com.cereal.sdk.component.llm.UserPart
import com.cereal.test.components.invalidOutput

/**
 * A fake model, the `responder` of the host's FakeLlmComponent: `host.llm.responder = model::respond`. Each turn is a
 * function of the request it answers, so a turn can pick a ref from the snapshot it was sent. Records every request.
 */
class ScriptedModel(
    vararg turns: suspend (LlmRequest) -> List<LlmToolCall>,
    private val usage: LlmUsage = LlmUsage(1000, 0, 0, 100),
    /** The responding model per turn; [MODEL] once the list runs out. */
    private val models: List<String> = emptyList(),
) {
    private val turns = ArrayDeque(turns.toList())
    val requests = mutableListOf<LlmRequest>()

    suspend fun respond(request: LlmRequest): LlmResponse {
        requests += request
        val turn = turns.removeFirstOrNull() ?: error("ScriptedModel: no turn left for request ${requests.size}")
        return response(turn(request), model = models.getOrElse(requests.size - 1) { MODEL }, usage = usage)
    }

    /** [respond], but the turns at these 1-based [steps] come back as the client's rejection: `InvalidOutput` carrying the turn. */
    fun rejecting(vararg steps: Int): suspend (LlmRequest) -> LlmResponse =
        { request -> respond(request).also { if (requests.size in steps) throw invalidOutput("{}", it.assistantTurn) } }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}

/** An [LlmResponse] with its own usage and model: cereal-test-utils' `llmResponse()` has zero usage and model `fake`. */
@OptIn(CerealPlatformApi::class)
fun response(
    calls: List<LlmToolCall> = emptyList(),
    text: String = "",
    stopReason: StopReason = if (calls.isEmpty()) StopReason.EndTurn else StopReason.ToolUse,
    model: String = ScriptedModel.MODEL,
    usage: LlmUsage = LlmUsage(1000, 0, 0, 100),
) = LlmResponse(text, calls, null, stopReason, usage, model, null)

private var callIds = 0

fun call(name: String, args: String = "{}") = LlmToolCall("call_${++callIds}", name, args)

/** The text of a user turn: its text and tool results. */
val LlmTurn.User.text get() = parts.mapNotNull { (it as? UserPart.Text)?.text ?: (it as? UserPart.ToolResult)?.text }.joinToString("\n")

/** The text the model was last sent: the latest tool result, or the goal message on the first turn. */
val LlmRequest.lastText: String
    get() = (turns.last() as? LlmTurn.User ?: error("request ends with an assistant turn")).text

/** The `[n]` ref of the element with this role and name in the latest snapshot. */
fun LlmRequest.ref(role: String, name: String): Int =
    Regex("""\[(\d+)] ${Regex.escape(role)} "${Regex.escape(name)}"""").find(lastText)?.groupValues?.get(1)?.toInt()
        ?: error("no $role \"$name\" in snapshot:\n$lastText")

/** Everything a request carries as text. */
fun LlmRequest.text() =
    system + "\n" +
        turns.joinToString("\n") { t ->
            when (t) {
                is LlmTurn.User -> t.text
                is LlmTurn.Assistant -> t.toolCalls.joinToString { "${it.name}${it.argumentsJson}" }
            }
        }

/** The tool results the model was last sent. */
val LlmRequest.lastResults: List<UserPart.ToolResult> get() = (turns.last() as LlmTurn.User).parts.filterIsInstance<UserPart.ToolResult>()

/** The images the model was last sent, beside the tool results. */
val LlmRequest.lastImages: List<UserPart.Image> get() = (turns.last() as LlmTurn.User).parts.filterIsInstance<UserPart.Image>()

/** The `tN` text ref of the static text, cell or heading with this role and name in the latest snapshot. */
fun LlmRequest.textRef(role: String, name: String): String =
    Regex("""\[(t\d+)] ${Regex.escape(role)} "${Regex.escape(name)}"""").find(lastText)?.groupValues?.get(1)
        ?: error("no text ref $role \"$name\" in snapshot:\n$lastText")
