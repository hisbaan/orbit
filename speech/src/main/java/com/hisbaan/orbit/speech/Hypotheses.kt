package com.hisbaan.orbit.speech

/**
 * Picks what was said from the recognizer's ranked hypotheses. The top one, except: the
 * recognizer drops a "percent" after a number from its top pick while ranking the right text
 * just below it ("set the lights to 30 brightness", then "… 30 percent brightness"). An
 * alternative that differs from the top only by a percent after a number is taken instead.
 */
internal fun pickHypothesis(ranked: List<String>): String? {
    val top = ranked.firstOrNull() ?: return null
    return ranked.drop(1).firstOrNull { it != top && withoutPercents(it) == top } ?: top
}

private val PERCENT_AFTER_NUMBER = Regex("""(\d)(%|\s+percent\b)""", RegexOption.IGNORE_CASE)

private fun withoutPercents(text: String): String = PERCENT_AFTER_NUMBER.replace(text, "$1")

/**
 * The utterance so far, from the recognizer's partials. It splits an utterance at pauses: it
 * finishes a segment, then its partials carry only the next one ("Set an alarm for 7 30", then
 * "Am"). Finished segments are kept and the next added after them, or a pause would drop
 * everything before it. The finish can arrive after the next segment's first partials, so a
 * partial that isn't the finished segment's text is kept as the next one.
 */
internal class Utterance {
    private var finished: String? = null
    private var open: String? = null

    val text: String? get() = join(finished, open)

    /** The segment in progress, so far. Punctuation alone (a "." between segments) is ignored. */
    fun partial(segment: String) {
        if (segment.any { it.isLetterOrDigit() }) open = segment
    }

    fun segmentDone(segment: String) {
        if (!segment.any { it.isLetterOrDigit() }) return
        val pending = open
        finished = join(finished, segment)
        if (pending == null || sameSegment(pending, segment)) open = null
    }

    /** The recognizer's final result: the last segment, or (if it starts with them) all of them. */
    fun result(text: String): String? {
        val before = finished
        if (before != null && text.startsWith(before)) return text
        segmentDone(text)
        return this.text
    }

    private companion object {
        fun join(a: String?, b: String?): String? = listOfNotNull(a, b).joinToString(" ").ifEmpty { null }

        /** Whether [partial] is an earlier version of [done]: most of its words are in it. */
        fun sameSegment(partial: String, done: String): Boolean {
            val doneWords = words(done).toSet()
            val partialWords = words(partial)
            return partialWords.isNotEmpty() && partialWords.count { it in doneWords } * 2 >= partialWords.size
        }

        fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
    }
}
