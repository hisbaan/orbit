package com.hisbaan.orbit.tools

/**
 * Finds what the user named among labels (apps, calendars), the way they'd say it: case,
 * spaces and punctuation don't matter ("power amp" is Poweramp). An exact match wins; else a
 * label that contains what was said. Never the reverse: "YouTube Music" isn't YouTube.
 */
internal object NameMatch {
    sealed interface Result<out T> {
        data class One<T>(val value: T) : Result<T>

        /** Several fit equally well: ask which. */
        data class Many<T>(val values: List<T>) : Result<T>

        data object None : Result<Nothing>
    }

    fun <T> find(candidates: List<T>, said: String, label: (T) -> String): Result<T> {
        val wanted = key(said)
        if (wanted.isEmpty()) return Result.None
        val exact = candidates.filter { key(label(it)) == wanted }
        if (exact.isNotEmpty()) return exact.toResult()
        return candidates.filter { wanted in key(label(it)) }.toResult()
    }

    private fun <T> List<T>.toResult(): Result<T> = when (size) {
        0 -> Result.None
        1 -> Result.One(single())
        else -> Result.Many(this)
    }

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
