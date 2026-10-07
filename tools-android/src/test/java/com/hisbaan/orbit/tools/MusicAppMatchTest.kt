package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicAppMatchTest {
    private val apps = listOf(
        "YouTube" to "com.google.android.youtube",
        "YouTube Music" to "com.google.android.apps.youtube.music",
        "Spotify" to "com.spotify.music",
        "Poweramp" to "com.maxmpz.audioplayer",
    )

    @Test
    fun `matches the app the user named`() {
        assertEquals("com.spotify.music", PlayMusicTool.matchApp(apps, "spotify"))
        assertEquals("com.google.android.apps.youtube.music", PlayMusicTool.matchApp(apps, "YouTube Music"))
        assertEquals("com.google.android.youtube", PlayMusicTool.matchApp(apps, "youtube"))
        assertEquals("com.maxmpz.audioplayer", PlayMusicTool.matchApp(apps, "power amp".replace(" ", "")))
        assertEquals(null, PlayMusicTool.matchApp(apps, "Tidal"))
    }
}
