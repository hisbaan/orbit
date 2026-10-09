package com.hisbaan.orbit.history

import com.hisbaan.orbit.agent.AgentEvent
import com.hisbaan.orbit.providers.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRecorderTest {
    private var enabled = true
    private var now = 0L
    private val saved = mutableListOf<Pair<TurnRecord, Boolean>>()
    private val recorder = HistoryRecorder(enabled = { enabled }, clock = { now++ }) { turn, starts -> saved += turn to starts }

    private fun step(prompt: String = "sys") = AgentEvent.ModelStep(1, "m", prompt, listOf("t"), 1, "", emptyList(), "stop", 5)

    @Test
    fun `a turn's events are saved together when it ends`() {
        recorder.turnStarted("button")
        recorder.record(HistoryEvent.Heard("pause"))
        recorder.agentEvent(AgentEvent.ConversationStarted)
        recorder.agentEvent(step())
        recorder.agentEvent(AgentEvent.ToolRan(ToolCall("1", "media", "{}"), "Paused.", 3, done = true))
        recorder.agentEvent(AgentEvent.Finished(AgentEvent.Finished.How.CONFIRMED))
        recorder.record(HistoryEvent.Reply("Paused."))
        recorder.turnEnded()

        val (turn, starts) = saved.single()
        assertTrue(starts)
        assertEquals("button", turn.source)
        assertEquals(
            listOf(HistoryEvent.Heard::class, HistoryEvent.ModelStep::class, HistoryEvent.ToolRan::class, HistoryEvent.Reply::class),
            turn.entries.map { it.event::class },
        )
        assertTrue(turn.entries.zipWithNext().all { (a, b) -> a.at < b.at })
    }

    @Test
    fun `an unchanged system prompt is stored once per conversation`() {
        recorder.turnStarted("button")
        recorder.agentEvent(AgentEvent.ConversationStarted)
        recorder.agentEvent(step())
        recorder.agentEvent(step())
        recorder.agentEvent(step("sys, later"))
        recorder.turnEnded()
        recorder.turnStarted("button")
        recorder.agentEvent(AgentEvent.ConversationStarted)
        recorder.agentEvent(step("sys, later"))
        recorder.turnEnded()

        val prompts = saved.map { (turn, _) -> turn.entries.map { (it.event as HistoryEvent.ModelStep).systemPrompt } }
        assertEquals(listOf(listOf("sys", null, "sys, later"), listOf("sys, later")), prompts)
    }

    @Test
    fun `the model forgetting mid-turn is noted, not a new conversation`() {
        recorder.turnStarted("button")
        recorder.agentEvent(step())
        recorder.agentEvent(AgentEvent.ConversationStarted)
        recorder.turnEnded()
        val (turn, starts) = saved.single()
        assertFalse(starts)
        assertEquals(HistoryEvent.MemoryExpired, turn.entries.last().event)
    }

    @Test
    fun `nothing is recorded while history is off`() {
        enabled = false
        recorder.turnStarted("button")
        recorder.record(HistoryEvent.Heard("pause"))
        recorder.turnEnded()
        assertTrue(saved.isEmpty())
    }
}
