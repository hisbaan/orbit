package com.hisbaan.orbit.settings

import com.hisbaan.orbit.tools.SavedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlaylistsTest {
    @Test
    fun `reads what earlier versions stored, and round-trips`() {
        // As written with org.json before.
        val stored = """[{"name":"Music","id":"PLabc123"},{"name":"Road trip","id":"PLxyz789"}]"""
        val playlists = listOf(SavedPlaylist("Music", "PLabc123"), SavedPlaylist("Road trip", "PLxyz789"))
        assertEquals(playlists, SavedPlaylists.decode(stored))
        assertEquals(playlists, SavedPlaylists.decode(SavedPlaylists.encode(playlists)))
    }

    @Test
    fun `bad data reads as no playlists, or skips the bad entry`() {
        assertEquals(emptyList<SavedPlaylist>(), SavedPlaylists.decode("not json"))
        assertEquals(listOf(SavedPlaylist("Music", "PLabc123")), SavedPlaylists.decode("""[{"name":"Music","id":"PLabc123"},{"name":"broken"}]"""))
    }
}
