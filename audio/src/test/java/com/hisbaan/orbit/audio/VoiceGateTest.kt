package com.hisbaan.orbit.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {
    private val chunk = MicCapture.SAMPLE_RATE / 50

    /** Feeds [ms] of audio at [dbfs]; returns whether each 20 ms chunk was let through. */
    private fun VoiceGate.feed(dbfs: Float, ms: Int): List<Boolean> = List(ms / 20) { admit(chunk, dbfs) }

    private fun VoiceGate.quietRoomThenUser(): VoiceGate = apply {
        startListen()
        feed(-55f, 300)
        feed(-20f, 1_500)
    }

    @Test
    fun `lets everything through before it has heard the voice`() {
        val gate = VoiceGate()
        gate.startListen()
        assertTrue(gate.feed(-55f, 300).all { it })
        assertTrue(gate.feed(-40f, 300).all { it })
        assertTrue(gate.summary(), gate.summary().contains("hadn't heard enough"))
    }

    @Test
    fun `silences a quieter voice after the user stops`() {
        val gate = VoiceGate().quietRoomThenUser()
        val tv = gate.feed(-40f, 2_000)
        assertTrue("hangover keeps the user's word endings", tv.take(10).all { it })
        assertTrue("the TV is held back after it", tv.drop(20).none { it })
        assertTrue(gate.summary(), gate.summary().contains("voice -20 dBFS"))
        assertTrue(gate.summary(), gate.summary().contains("up to -40)"))
    }

    @Test
    fun `passes sound within the margin of the voice`() {
        val gate = VoiceGate().quietRoomThenUser()
        assertTrue(gate.feed(-30f, 2_000).all { it })
    }

    @Test
    fun `a knock doesn't raise the voice level`() {
        val gate = VoiceGate()
        gate.startListen()
        gate.feed(-55f, 300)
        gate.feed(-32f, 800)
        gate.feed(-3f, 20)
        gate.feed(-55f, 400)
        assertTrue("the user's next words still pass", gate.feed(-32f, 500).all { it })
    }

    @Test
    fun `a follow-up listen keeps the voice level`() {
        val gate = VoiceGate().quietRoomThenUser()
        gate.startListen()
        val talk = gate.feed(-42f, 3_000)
        assertFalse("people across the room aren't taken for an answer", talk.any { it })
        assertTrue("the user answering is", gate.feed(-22f, 500).all { it })
    }

    @Test
    fun `summary covers only this listen`() {
        val gate = VoiceGate().quietRoomThenUser()
        gate.feed(-45f, 1_000)
        gate.startListen()
        gate.feed(-21f, 1_000)
        assertTrue(gate.summary(), gate.summary().matches(Regex("Voice gate: voice -20 dBFS, floor -5\\d, passing above -35; held back 0%")))
    }
}
