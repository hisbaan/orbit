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

    @Test
    fun `keeps the segment before a pause`() {
        // As heard: "set an alarm for 7:30", a pause, "am".
        val u = Utterance()
        u.partial("Set an alarm for 7 30")
        u.segmentDone("Set an alarm for 7 30")
        u.partial("Hey")
        u.partial("Bam")
        assertEquals("Set an alarm for 7 30 Bam", u.text)
    }

    @Test
    fun `keeps the next segment when the last one finishes after it started`() {
        val u = Utterance()
        u.partial("Set an alarm for 7 30")
        u.partial(".")
        u.partial("am")
        u.segmentDone("Set an alarm for 7:30")
        assertEquals("Set an alarm for 7:30 am", u.text)
        u.segmentDone("am")
        assertEquals("Set an alarm for 7:30 am", u.text)
    }

    @Test
    fun `a finished segment replaces its own partial`() {
        val u = Utterance()
        u.partial("Send an alarm for 7 30")
        u.segmentDone("Set an alarm for 7:30")
        assertEquals("Set an alarm for 7:30", u.text)
        u.partial("Set the lights to 30 brightness")
        u.segmentDone("Set the lights to 30 percent brightness")
        assertEquals("Set an alarm for 7:30 Set the lights to 30 percent brightness", u.text)
    }

    @Test
    fun `final result`() {
        val u = Utterance()
        assertEquals("Turn on the lights", u.result("Turn on the lights"))
        val v = Utterance()
        v.segmentDone("Set an alarm for 7:30")
        assertEquals("Set an alarm for 7:30 am", v.result("am"))
        val w = Utterance()
        w.segmentDone("Set an alarm for 7:30")
        assertEquals("Set an alarm for 7:30 am", w.result("Set an alarm for 7:30 am"))
    }
}
