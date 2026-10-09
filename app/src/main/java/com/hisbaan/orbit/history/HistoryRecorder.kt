package com.hisbaan.orbit.history

import com.hisbaan.orbit.agent.AgentEvent

/**
 * Collects one turn's events for the conversation history: what started it, what Orbit heard,
 * each model and tool call, what it said and did after. Hands the finished turn to [save],
 * with whether the model started a new conversation in it. Records nothing while [enabled] is
 * false; the store also drops turns while the phone has no screen lock.
 */
class HistoryRecorder(
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val save: (turn: TurnRecord, startsConversation: Boolean) -> Unit,
) {
    private class Turn(val startedAt: Long, val source: String) {
        val entries = mutableListOf<Entry>()
        var startsConversation = false
    }

    private var turn: Turn? = null

    /** The system prompt last recorded, so an unchanged one isn't stored again. */
    private var lastPrompt: String? = null

    @Synchronized
    fun turnStarted(source: String) {
        turn = if (enabled()) Turn(clock(), source) else null
    }

    @Synchronized
    fun record(event: HistoryEvent) {
        turn?.entries?.add(Entry(clock(), event))
    }

    @Synchronized
    fun agentEvent(event: AgentEvent) {
        val turn = turn ?: return
        if (event == AgentEvent.ConversationStarted) {
            lastPrompt = null
            // Before the model's first call in this turn: the turn starts a conversation. After
            // it, the model forgot the earlier messages partway through, which is worth seeing.
            if (turn.entries.none { it.event is HistoryEvent.ModelStep || it.event is HistoryEvent.ModelFailed }) {
                turn.startsConversation = true
            } else {
                record(HistoryEvent.MemoryExpired)
            }
            return
        }
        val recorded = event.toHistory(lastPrompt) ?: return
        if (event is AgentEvent.ModelStep) lastPrompt = event.systemPrompt
        record(recorded)
    }

    @Synchronized
    fun turnEnded() {
        val turn = turn ?: return
        this.turn = null
        save(TurnRecord(turn.startedAt, clock(), turn.source, turn.entries.toList()), turn.startsConversation)
    }
}
