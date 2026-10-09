package com.hisbaan.orbit.agent

import com.hisbaan.orbit.providers.ToolCall

/**
 * What happened inside [Agent.respond], step by step, for the conversation history the user
 * can look back through. Unlike [com.hisbaan.orbit.diagnostics.EventLog] lines these carry
 * everything: what was said, tool arguments and results ([Tool.privateResult] ones too), so
 * whatever receives them must keep them safe.
 */
sealed interface AgentEvent {
    /** The model has no earlier messages: this one starts a conversation (the first, or after the history expired). */
    data object ConversationStarted : AgentEvent

    /** One model call: what it was sent ([systemPrompt], [tools] offered, [messages] after the system prompt) and what came back. */
    data class ModelStep(
        val step: Int,
        val model: String,
        val systemPrompt: String,
        val tools: List<String>,
        val messages: Int,
        val text: String,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val durationMs: Long,
    ) : AgentEvent

    data class ModelFailed(val step: Int, val model: String, val error: String, val durationMs: Long) : AgentEvent

    /**
     * A tool call and what the model was told about it ([result]). [refused]: why it never ran
     * (unknown tool, bad arguments, phone locked). [error]: it threw, and [result] says so.
     * [held]: the ref and description of an action now waiting for the user's yes.
     */
    data class ToolRan(
        val call: ToolCall,
        val result: String,
        val durationMs: Long,
        val done: Boolean,
        val refused: String? = null,
        val error: String? = null,
        val held: String? = null,
        val afterTurn: String? = null,
        val cards: List<String> = emptyList(),
        val images: List<String> = emptyList(),
    ) : AgentEvent

    /** Text the model wrote alongside tool calls, taken back because the turn went on. */
    data class TextWithdrawn(val text: String) : AgentEvent

    /** The user's yes ran a held action. */
    data class Confirmed(val ref: String, val description: String) : AgentEvent

    data class Finished(val how: How) : AgentEvent {
        enum class How {
            /** The model answered in text. */
            ANSWERED,

            /** Every action went through; the confirmations it wrote were the reply, with no further call. */
            CONFIRMED,

            /** Out of steps. */
            GAVE_UP,
        }
    }
}
