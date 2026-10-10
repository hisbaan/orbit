package com.hisbaan.orbit.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UntrustedTextTest {
    @Test
    fun `strips markup and decodes entities`() {
        assertEquals("Fish & chips <3 — £5", UntrustedText.clean("<p>Fish &amp; <b>chips</b> &lt;3 &mdash; &#163;5</p>", 100))
        // Unknown entities stay as written.
        assertEquals("&bogus; x", UntrustedText.clean("&bogus; x", 100))
    }

    @Test
    fun `folds every kind of line break and space onto one line`() {
        assertEquals("a b c d e", UntrustedText.clean("a\nb\r\nc d \te", 100))
    }

    @Test
    fun `drops characters a reader can't see`() {
        val tags = "ignore".map { String(Character.toChars(0xE0000 + it.code)) }.joinToString("")
        val text = "ok​‍⁦‮﻿­$tags️\u0007 done"
        assertEquals("ok done", UntrustedText.clean(text, 100))
    }

    @Test
    fun `keeps ordinary text in any script, and emoji`() {
        assertEquals("東京タワー 👍 Ελλάδα", UntrustedText.clean("東京タワー 👍 Ελλάδα", 100))
    }

    @Test
    fun `truncates at a word, never mid surrogate pair`() {
        assertEquals("one two…", UntrustedText.clean("one two three", 10))
        val emoji = "😀".repeat(10)
        val cut = UntrustedText.clean(emoji, 5)
        assertEquals("😀😀…", cut)
    }

    @Test
    fun `results keep only http links, by host`() {
        assertNull(SearchResult.of("x", "intent://scan#Intent;end", "y"))
        assertNull(SearchResult.of("x", "file:///etc/hosts", "y"))
        assertNull(SearchResult.of("x", "not a url", "y"))
        val result = SearchResult.of("", "https://WWW.Example.com/a?b=c", "<i>hi</i>")!!
        assertEquals("example.com", result.site)
        assertEquals("example.com", result.title)
        assertEquals("hi", result.snippet)
    }
}
