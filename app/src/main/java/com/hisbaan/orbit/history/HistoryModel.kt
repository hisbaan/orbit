package com.hisbaan.orbit.history

import com.hisbaan.orbit.agent.AgentEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing that happened in a turn, as kept in the conversation history: what Orbit heard,
 * each model and tool call, what it said, what it did after. Stored (encrypted) as JSON, so
 * names are fixed with [SerialName] and fields only ever added with defaults.
 */
@Serializable
sealed interface HistoryEvent {
    /** What the user said, or typed ([typed]: the in-app text box or the debug hook). */
    @Serializable
    @SerialName("heard")
    data class Heard(val text: String, val typed: Boolean = false) : HistoryEvent

    /** The listen ended without words ([what]): silence, or [failed] (the microphone, the recognizer). */
    @Serializable
    @SerialName("heard_nothing")
    data class HeardNothing(val what: String, val failed: Boolean) : HistoryEvent

    /** Orbit listened again without a press: [why] the reply asked a question, or the user interrupted. */
    @Serializable
    @SerialName("listening_again")
    data class ListeningAgain(val why: String) : HistoryEvent

    /** The model's earlier messages expired mid-turn: it carries on without them. */
    @Serializable
    @SerialName("memory_expired")
    data object MemoryExpired : HistoryEvent

    @Serializable
    @SerialName("model_step")
    data class ModelStep(
        val step: Int,
        val model: String,
        /** Null when it's the same as the conversation's previous one. */
        val systemPrompt: String? = null,
        val tools: List<String> = emptyList(),
        val messages: Int = 0,
        val text: String = "",
        val toolCalls: List<Call> = emptyList(),
        val finishReason: String? = null,
        val durationMs: Long = 0,
    ) : HistoryEvent

    @Serializable
    @SerialName("model_failed")
    data class ModelFailed(val step: Int, val model: String, val error: String, val durationMs: Long = 0) : HistoryEvent

    @Serializable
    @SerialName("tool")
    data class ToolRan(
        val call: Call,
        val result: String,
        val durationMs: Long = 0,
        val done: Boolean = false,
        val refused: String? = null,
        val error: String? = null,
        val held: String? = null,
        val afterTurn: String? = null,
        val cards: List<String> = emptyList(),
        val images: List<String> = emptyList(),
    ) : HistoryEvent

    @Serializable
    @SerialName("text_withdrawn")
    data class TextWithdrawn(val text: String) : HistoryEvent

    @Serializable
    @SerialName("confirmed")
    data class Confirmed(val ref: String, val description: String) : HistoryEvent

    @Serializable
    @SerialName("gave_up")
    data object GaveUp : HistoryEvent

    /** The reply shown and spoken; [interrupted] if the user cut it off. */
    @Serializable
    @SerialName("reply")
    data class Reply(val text: String, val interrupted: Boolean = false) : HistoryEvent

    /** Something Orbit said that isn't the model's reply ("I couldn't reach the model."), with the [error] shown. */
    @Serializable
    @SerialName("said")
    data class Said(val text: String, val error: String? = null) : HistoryEvent

    @Serializable
    @SerialName("agent_failed")
    data class AgentFailed(val error: String) : HistoryEvent

    /** Orbit asked the user to unlock: [forWhat] a tool mid-turn, or an action after it. */
    @Serializable
    @SerialName("unlock")
    data class UnlockAsked(val forWhat: String, val unlocked: Boolean) : HistoryEvent

    @Serializable
    @SerialName("after_turn")
    data class AfterTurn(val description: String, val outcome: Outcome, val error: String? = null) : HistoryEvent {
        enum class Outcome { RAN, SKIPPED_LOCKED, FAILED }
    }

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : HistoryEvent

    @Serializable
    @SerialName("failed")
    data class Failed(val error: String) : HistoryEvent

    @Serializable
    data class Call(val id: String, val name: String, val arguments: String)

    /** Something went wrong here: shown in the error color and flags the conversation in the list. */
    val problem: Boolean
        get() = when (this) {
            is HeardNothing -> failed
            is ModelFailed, GaveUp, is AgentFailed, is Failed -> true
            is ToolRan -> refused != null || error != null
            is Said -> error != null
            is UnlockAsked -> !unlocked
            is AfterTurn -> outcome != AfterTurn.Outcome.RAN
            else -> false
        }
}

/** [event] at wall time [at]. */
@Serializable
data class Entry(val at: Long, val event: HistoryEvent)

/** One trigger, from the press (or typed question) to the last action after the reply. */
@Serializable
data class TurnRecord(
    val startedAt: Long,
    val endedAt: Long,
    /** What started it: "in-app button", "VOICE_COMMAND", "typed". */
    val source: String,
    val entries: List<Entry>,
) {
    /** The model was called (or tried to be), so the agent decided which conversation this is. */
    val reachedModel: Boolean get() = entries.any { it.event is HistoryEvent.ModelStep || it.event is HistoryEvent.ModelFailed }
}

/** A conversation read back from the store: turns the model saw as one exchange, oldest first. */
data class StoredConversation(val id: String, val startedAt: Long, val turns: List<TurnRecord>) {
    val firstWords: String? get() = turns.firstNotNullOfOrNull { turn -> turn.entries.firstNotNullOfOrNull { (it.event as? HistoryEvent.Heard)?.text } }
    val toolCalls: Int get() = turns.sumOf { turn -> turn.entries.count { it.event is HistoryEvent.ToolRan } }
    val hasProblem: Boolean get() = turns.any { turn -> turn.entries.any { it.event.problem } }
}

internal val historyJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

/** The history's copy of [event]. [lastPrompt] is the system prompt last recorded, so an unchanged one isn't stored again. */
internal fun AgentEvent.toHistory(lastPrompt: String?): HistoryEvent? = when (this) {
    AgentEvent.ConversationStarted -> null
    is AgentEvent.ModelStep -> HistoryEvent.ModelStep(
        step = step,
        model = model,
        systemPrompt = systemPrompt.takeIf { it != lastPrompt },
        tools = tools,
        messages = messages,
        text = text,
        toolCalls = toolCalls.map { HistoryEvent.Call(it.id, it.name, it.argumentsJson) },
        finishReason = finishReason,
        durationMs = durationMs,
    )
    is AgentEvent.ModelFailed -> HistoryEvent.ModelFailed(step, model, error, durationMs)
    is AgentEvent.ToolRan -> HistoryEvent.ToolRan(
        call = HistoryEvent.Call(call.id, call.name, call.argumentsJson),
        result = result,
        durationMs = durationMs,
        done = done,
        refused = refused,
        error = error,
        held = held,
        afterTurn = afterTurn,
        cards = cards,
        images = images,
    )
    is AgentEvent.TextWithdrawn -> HistoryEvent.TextWithdrawn(text)
    is AgentEvent.Confirmed -> HistoryEvent.Confirmed(ref, description)
    is AgentEvent.Finished -> if (how == AgentEvent.Finished.How.GAVE_UP) HistoryEvent.GaveUp else null
}
