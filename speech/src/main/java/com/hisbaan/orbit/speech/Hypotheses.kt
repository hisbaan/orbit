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

