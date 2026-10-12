package com.hisbaan.orbit.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HypothesesTest {
    @Test
    fun `takes the top hypothesis`() {
        assertEquals("Turn off the lights", pickHypothesis(listOf("Turn off the lights", "Turn of the lights")))
        assertNull(pickHypothesis(emptyList()))
    }

    @Test
    fun `restores a percent dropped from the middle`() {
        // Seen from the on-device recognizer, with light background noise.
        val ranked = listOf(
            "Set the lights to 30 brightness",
            "Set the lights to 30 percent brightness",
            "Set the lights to 30% brightness",
            "Set the lights to 30 brightness brightness",
        )
        assertEquals("Set the lights to 30 percent brightness", pickHypothesis(ranked))
    }

    @Test
    fun `restores a percent dropped from the end`() {
        assertEquals("Set volume to 20%", pickHypothesis(listOf("Set volume to 20", "Set volume to 20%")))
        assertEquals("Set volume to 20 Percent.", pickHypothesis(listOf("Set volume to 20.", "Set volume to 20 Percent.")))
    }

    @Test
    fun `doesn't take an alternative that differs otherwise`() {
        assertEquals("Set volume to 20", pickHypothesis(listOf("Set volume to 20", "Set volume to 25%", "Set the volume to 20%")))
        assertEquals("Set volume to twenty", pickHypothesis(listOf("Set volume to twenty", "Set volume to twenty percent")))
    }
}
