package com.hisbaan.orbit.ytmusic

import com.hisbaan.orbit.ytmusic.YtmResult.Type
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real search responses (2026-10-06), trimmed of tracking and thumbnail data. */
class YouTubeMusicSearchTest {
    private fun fixture(name: String) =
        YouTubeMusicSearch.parse(Json.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject)

    @Test
    fun `parses the top result card and list items in rank order`() {
        val results = fixture("search_eden.json")
        val top = results.first()
        assertEquals(Type.ARTIST, top.type)
        assertEquals("EDEN", top.title)
        assertTrue(top.playlistId!!.startsWith("RDAO")) // artist radio
        assertTrue(results.any { it.type == Type.SONG && it.title == "Wake Up" && it.subtitle.startsWith("EDEN") })
        assertTrue(results.any { it.type == Type.ALBUM && it.playlistId!!.startsWith("OLAK5uy_") })
    }

    @Test
    fun `song request picks the song over the top video`() {
        val results = fixture("search_bohemian_rhapsody.json")
        assertEquals(Type.VIDEO, results.first().type)
        val song = YouTubeMusicSearch.pick(results, kind = "song", artist = "Queen")!!
        assertEquals(Type.SONG, song.type)
        assertEquals("Bohemian Rhapsody", song.title)
        assertEquals("https://music.youtube.com/watch?v=BSTsnWoslP4", song.playUrl)
    }

    @Test
    fun `album request plays the album's playlist`() {
        val album = YouTubeMusicSearch.pick(fixture("search_vertigo_eden_album.json"), kind = "album", artist = "EDEN")!!
        assertEquals("vertigo", album.title)
        assertEquals("https://music.youtube.com/watch?list=OLAK5uy_krUEwy8buZkaDoSw_9DwBReiXxa1zxpmU", album.playUrl)
    }

    @Test
    fun `artist filter narrows same-named results`() {
        val results = fixture("search_eden.json")
        val song = YouTubeMusicSearch.pick(results, kind = "song", artist = "EDEN")!!
        // Not "Lyf Teen Lyf", which ranks higher but is by a different artist credited as "Eden".
        assertEquals("Wake Up", song.title)
    }

    @Test
    fun `episodes and podcasts are never picked`() {
        val results = fixture("search_bohemian_rhapsody.json")
        assertTrue(results.none { it.subtitle.startsWith("Episode") && it.type != Type.OTHER })
        assertTrue(YouTubeMusicSearch.pick(results, kind = null, artist = null)!!.type != Type.OTHER)
    }
}
