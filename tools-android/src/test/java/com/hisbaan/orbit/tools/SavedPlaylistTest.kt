package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlaylistTest {
    @Test
    fun `reads the playlist id from share links and bare ids`() {
        assertEquals(
            "PLabc123_-XYZdef",
            SavedPlaylist.parsePlaylistId("https://music.youtube.com/playlist?list=PLabc123_-XYZdef&si=x1y2"),
        )
        assertEquals("PLabc123_-XYZdef", SavedPlaylist.parsePlaylistId("https://youtube.com/watch?v=abc&list=PLabc123_-XYZdef"))
        assertEquals("PLabc123_-XYZdef", SavedPlaylist.parsePlaylistId("  PLabc123_-XYZdef "))
        assertNull(SavedPlaylist.parsePlaylistId("my music"))
        assertNull(SavedPlaylist.parsePlaylistId("https://music.youtube.com/channel/UC123"))
    }
}
