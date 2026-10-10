package com.hisbaan.orbit.websearch

/**
 * Cleans text from web pages before the model sees it: strips markup, decodes entities, drops
 * characters that are invisible on screen but read by the model (zero-width, bidi controls,
 * Unicode tag characters, variation selectors, private use), and folds everything onto one line.
 *
 * This doesn't make the text safe to obey; it removes the ways to hide instructions from a
 * person checking the results and to fake the structure around them. The agent's handling of
 * untrusted results is what limits what they can make the model do.
 */
object UntrustedText {
    fun clean(text: String, maxChars: Int): String {
        val decoded = decodeEntities(TAG.replace(text, " "))
        val visible = buildString(decoded.length) {
            var i = 0
            while (i < decoded.length) {
                val cp = decoded.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                    hidden(cp) -> {}
                    else -> appendCodePoint(cp)
                }
            }
        }
        return truncate(SPACES.replace(visible, " ").trim(), maxChars)
    }

    private fun hidden(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        // Controls, formatting (zero-width, bidi, U+E0000 tags, soft hyphen), private use,
        // unassigned and lone surrogates.
        Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> true
        // Variation selectors: invisible, and a run of them can spell out hidden text.
        else -> cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    }

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var cut = text.lastIndexOf(' ', maxChars - 1).takeIf { it > maxChars / 2 } ?: (maxChars - 1)
        // Don't split a surrogate pair.
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.substring(0, cut).trimEnd() + "…"
    }

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val body = m.groupValues[1]
        val cp = when {
            body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)
            body.startsWith("#") -> body.drop(1).toIntOrNull()
            else -> NAMED[body.lowercase()]
        }
        if (cp == null || !Character.isValidCodePoint(cp)) m.value else String(Character.toChars(cp))
    }

    private val TAG = Regex("<[^>]{0,2000}>")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val SPACES = Regex(" {2,}")
    private val NAMED = mapOf(
        "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
        "nbsp" to ' '.code, "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026, "rsquo" to 0x2019,
        "lsquo" to 0x2018, "rdquo" to 0x201D, "ldquo" to 0x201C, "middot" to 0xB7, "deg" to 0xB0,
    )
}
