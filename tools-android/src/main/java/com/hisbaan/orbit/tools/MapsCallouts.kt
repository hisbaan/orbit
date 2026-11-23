package com.hisbaan.orbit.tools

import kotlin.math.max
import kotlin.math.min

/** A line of text read off a screenshot, with its box in the screenshot's pixels. */
internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = bottom - top
}

/**
 * A place Maps labels on the map after a search along the route: "Shell" over "+4 min",
 * sometimes with a fuel price ("$1.74/Regular") between them. [addedMinutes] is 0 for Maps'
 * "Quick detour". ([x], [y]) is the label's middle, where a tap adds the place as a stop.
 */
internal data class Callout(val name: String, val addedMinutes: Int, val price: String?, val x: Int, val y: Int) {
    fun describe(): String = buildString {
        append(name)
        append(if (addedMinutes == 0) " (quick detour" else " (+$addedMinutes min")
        price?.let { append(", ").append(it) }
        append(")")
    }
}

/**
 * Finds the labels a search along the route puts on the map, among all the text a screenshot
 * holds (street names, the turn banner, ETA). A label is anchored on its detour line, which
 * nothing else on the navigation screen looks like, and named by the line just above it.
 * Verified on Maps 26.44.
 */
internal object MapsCallouts {
    private val DETOUR = Regex("""^\+\s*(?:(\d+)\s*h(?:r|our)?s?\s*)?(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val QUICK = Regex("""^(quick|no) detour""", RegexOption.IGNORE_CASE)
    private val PRICE = Regex("""^\$?\d+[.,]\d+\s*(¢|/)|/\s*(regular|premium|diesel|midgrade)""", RegexOption.IGNORE_CASE)

    fun find(lines: List<OcrLine>): List<Callout> = lines.mapNotNull { line ->
        val minutes = addedMinutes(line.text.trim()) ?: return@mapNotNull null
        val above = stackAbove(line, lines)
        val price = above.firstOrNull { PRICE.containsMatchIn(it.text) }
        val name = above.firstOrNull { it !== price } ?: return@mapNotNull null
        val box = listOfNotNull(line, name, price)
        Callout(
            name = name.text.trim(),
            addedMinutes = minutes,
            price = price?.text?.trim(),
            x = (box.minOf { it.left } + box.maxOf { it.right }) / 2,
            y = (box.minOf { it.top } + box.maxOf { it.bottom }) / 2,
        )
    }.distinctBy { Triple(it.name, it.addedMinutes, it.price) }.sortedBy { it.addedMinutes }

    private fun addedMinutes(text: String): Int? {
        if (QUICK.containsMatchIn(text)) return 0
        val match = DETOUR.find(text) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        return hours * 60 + match.groupValues[2].toInt()
    }

    /** Up to two lines stacked right above [line] in the same label: nearest first. */
    private fun stackAbove(line: OcrLine, lines: List<OcrLine>): List<OcrLine> {
        val stack = mutableListOf<OcrLine>()
        var below = line
        repeat(2) {
            val next = lines
                .filter { it !== below && it !in stack && it.bottom <= below.top + below.height / 2 }
                .filter { overlapsHorizontally(it, below) && below.top - it.bottom < below.height }
                .maxByOrNull { it.bottom } ?: return stack
            stack += next
            below = next
        }
        return stack
    }

    private fun overlapsHorizontally(a: OcrLine, b: OcrLine) = min(a.right, b.right) - max(a.left, b.left) > 0
}
