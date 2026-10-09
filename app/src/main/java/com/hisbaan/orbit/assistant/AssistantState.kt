package com.hisbaan.orbit.assistant

import com.hisbaan.orbit.agent.Card

enum class Phase(val label: String) {
    IDLE("Ready"),
    STARTING("Connecting…"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    SPEAKING("Speaking"),
    FINISHING("Finishing…"),
}

data class AssistantState(
    val phase: Phase = Phase.IDLE,
    val partialTranscript: String = "",
    val transcript: String? = null,
    val reply: String? = null,
    val actions: List<String> = emptyList(),
    val error: String? = null,
    /** Shown under the reply, e.g. a weather card. */
    val cards: List<Card> = emptyList(),
)
