package com.hisbaan.orbit.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceChunkerTest {
    /** Feeds [text] in small pieces, as a stream would, and collects every chunk. */
    private fun chunk(text: String, pieceSize: Int = 3): List<String> {
        val chunker = SentenceChunker()
        val out = text.chunked(pieceSize).flatMap(chunker::add).toMutableList()
        chunker.flush()?.let(out::add)
        return out
    }

    @Test
    fun `splits sentences as they complete`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.add("It's 21 degrees"))
        assertEquals(emptyList<String>(), chunker.add(" and sunny."))
        assertEquals(listOf("It's 21 degrees and sunny."), chunker.add(" Rain"))
        assertEquals("Rain", chunker.flush())
    }

    @Test
    fun `keeps numbers, abbreviations and ellipses inside a sentence`() {
        assertEquals(
            listOf("Dr. Smith said it's 3.5 km, e.g. past the U.S. border.", "Well... maybe!", "Okay?"),
            chunk("Dr. Smith said it's 3.5 km, e.g. past the U.S. border. Well... maybe! Okay?"),
        )
    }

    @Test
    fun `handles closing quotes and newlines`() {
        assertEquals(listOf("He said \"stop.\"", "Then", "left."), chunk("He said \"stop.\" Then\nleft."))
    }

    @Test
    fun `strips markdown`() {
        assertEquals(listOf("Playing Wake Up by EDEN."), chunk("**Playing** `Wake Up` by EDEN."))
    }

    @Test
    fun `breaks a long run at a clause`() {
        val long = "word ".repeat(30) + "then, " + "more ".repeat(10)
        val out = chunk(long)
        assertEquals(2, out.size)
        assertEquals(true, out[0].endsWith("then,"))
    }
}
