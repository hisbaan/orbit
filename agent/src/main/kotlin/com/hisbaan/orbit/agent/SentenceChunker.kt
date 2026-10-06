package com.hisbaan.orbit.agent

/**
 * Splits streamed reply text into speakable pieces, so text-to-speech can start on the first
 * sentence while the rest is still arriving.
 */
class SentenceChunker(private val maxChunk: Int = 160) {
    private val buffer = StringBuilder()

    /** Adds streamed text; returns the sentences it completed, in order. */
    fun add(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val end = boundary() ?: break
            take(end)?.let(out::add)
        }
        return out
    }

    /** Whatever is left once the stream has ended. */
    fun flush(): String? = take(buffer.length)

    private fun take(end: Int): String? {
        val piece = clean(buffer.substring(0, end))
        buffer.delete(0, end)
        return piece.takeIf { it.isNotEmpty() }
    }

    /** End (exclusive) of the first complete sentence in the buffer, or null to wait for more. */
    private fun boundary(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n') return i + 1
            if (c !in TERMINATORS) continue
            var j = i + 1
            while (j < buffer.length && buffer[j] in CLOSERS) j++
            // A sentence ends only where whitespace follows: "3.5" and "..." don't.
            if (j >= buffer.length) return null
            if (!buffer[j].isWhitespace()) continue
            if (c == '.' && buffer.getOrNull(i - 1) != '.' && isAbbreviation(i)) continue
            // "Well... maybe": a lowercase word next continues the sentence.
            var k = j
            while (k < buffer.length && buffer[k].isWhitespace() && buffer[k] != '\n') k++
            if (k >= buffer.length) return null
            if (buffer[k].isLowerCase()) continue
            return j
        }
        // A long run without a sentence end: break at a clause so speech isn't held up.
        if (buffer.length > maxChunk) {
            val clause = buffer.lastIndexOfAny(charArrayOf(',', ';', ':'), maxChunk)
            val cut = if (clause > 0) clause else buffer.lastIndexOf(" ", maxChunk)
            if (cut > 0) return cut + 1
        }
        return null
    }

    /** "Dr.", "e.g.", "U.S.", initials: a period that doesn't end the sentence. */
    private fun isAbbreviation(period: Int): Boolean {
        var start = period
        while (start > 0 && !buffer[start - 1].isWhitespace()) start--
        val word = buffer.substring(start, period)
        return (word.length == 1 && word[0].isUpperCase()) || '.' in word || word.lowercase() in ABBREVIATIONS
    }

    companion object {
        private const val TERMINATORS = ".!?…"
        private const val CLOSERS = "\"')]”’"
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "vs", "jr", "sr", "mt", "no", "approx")

        /** Strips markdown the model may emit despite the prompt; TTS would read it out. */
        private fun clean(text: String): String =
            text.replace(Regex("[*#`]+"), "").replace(Regex("\\s+"), " ").trim()
    }
}
