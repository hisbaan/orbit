package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class NameMatchTest {
    private val apps = listOf("YouTube", "YouTube Music", "Spotify", "Poweramp", "Clock")

    private fun find(said: String, from: List<String> = apps) = NameMatch.find(from, said) { it }

    @Test
    fun `an exact name wins, whatever the case, spaces or punctuation`() {
        assertEquals(NameMatch.Result.One("Spotify"), find("spotify"))
        assertEquals(NameMatch.Result.One("YouTube"), find("youtube")) // not YouTube Music too
        assertEquals(NameMatch.Result.One("YouTube Music"), find("YouTube music"))
        assertEquals(NameMatch.Result.One("Poweramp"), find("power amp"))
    }

    @Test
    fun `part of a name matches, and several matches are reported`() {
        assertEquals(NameMatch.Result.One("Poweramp"), find("power"))
        assertEquals(NameMatch.Result.Many(listOf("YouTube", "YouTube Music")), find("tube"))
        assertEquals(NameMatch.Result.Many(listOf("Clock", "Clock")), find("clock", listOf("Clock", "Clock")))
    }

    @Test
    fun `a longer name doesn't match a shorter app`() {
        // "YouTube Music" isn't installed: don't play it in YouTube.
        assertEquals(NameMatch.Result.None, find("YouTube Music", listOf("YouTube", "Spotify")))
        assertEquals(NameMatch.Result.None, find("Tidal"))
        assertEquals(NameMatch.Result.None, find("  "))
    }
}
