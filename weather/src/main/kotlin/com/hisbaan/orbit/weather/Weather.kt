package com.hisbaan.orbit.weather

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A forecast source. Implementations differ in coverage, resolution, speed and whether they need a key. */
interface WeatherProvider {
    /** Shown to the model and in logs, e.g. "Open-Meteo". */
    val name: String

    /** Current conditions, the next [hours] hours and [days] days (including today), in the place's local time. */
    suspend fun forecast(latitude: Double, longitude: Double, days: Int, imperial: Boolean, hours: Int = 12): Forecast
}

data class Place(val name: String, val region: String?, val country: String?, val latitude: Double, val longitude: Double) {
    val label: String get() = listOfNotNull(name, region?.takeIf { it != name }, country).joinToString(", ")
}

data class Units(val temperature: String, val wind: String, val precipitation: String) {
    companion object {
        val METRIC = Units("°C", "km/h", "mm")
        val IMPERIAL = Units("°F", "mph", "in")
    }
}

/** Broad sky state, for icons; providers map their own codes onto it. */
enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, SLEET, THUNDERSTORM, WIND, UNKNOWN }

/** [text] is the provider's own wording ("light rain showers"), [sky] the broad kind. */
data class Condition(val sky: Sky, val text: String) {
    companion object {
        val UNKNOWN = Condition(Sky.UNKNOWN, "unknown")
    }
}

/** Times are local to the place: "2026-10-06T11:30". [humidity] and chances are percentages. */
data class Current(
    val time: String,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Double?,
    val condition: Condition,
    val wind: Double?,
    val gusts: Double?,
)

data class Hour(val time: String, val temperature: Double?, val precipitationChance: Double?, val condition: Condition, val wind: Double?)

data class Day(
    val date: String,
    val condition: Condition,
    val max: Double?,
    val min: Double?,
    val precipitationChance: Double?,
    val precipitation: Double?,
    /** The strongest sustained wind. */
    val maxWind: Double?,
    val sunrise: String?,
    val sunset: String?,
    /** The strongest gust, where the provider gives one. */
    val gusts: Double? = null,
)

data class Forecast(val current: Current, val hours: List<Hour>, val days: List<Day>, val units: Units) {
    /**
     * Plain text for the model: now, the next [maxHours] hours, then [maxDays] days. Times are
     * local to the place.
     */
    fun describe(place: String, maxHours: Int = Int.MAX_VALUE, maxDays: Int = Int.MAX_VALUE): String = buildString {
        val hours = hours.take(maxHours)
        val days = days.take(maxDays)
        val t = units.temperature
        append("Weather for $place (local time ${current.time.timePart()}).\n")
        append("Now: ${current.condition.text}, ${current.temperature.whole()}$t")
        current.feelsLike?.let { append(" (feels like ${it.whole()}$t)") }
        current.wind?.let { append(", wind ${it.whole()} ${units.wind}") }
        current.gusts?.let { append(" gusting ${it.whole()} ${units.wind}") }
        current.humidity?.let { append(", humidity ${it.whole()}%") }
        append(".\n")
        if (hours.isNotEmpty()) {
            append("Next hours: ")
            append(
                hours.joinToString("; ") { h ->
                    "${h.time.timePart()} ${h.temperature.whole()}$t ${h.condition.text}" +
                        (h.precipitationChance?.let { ", precipitation ${it.whole()}%" } ?: "")
                },
            )
            append(".\n")
        }
        val today = current.time.take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (d in days) {
            append("${dayName(d.date, today)}: ${d.condition.text}, ${d.min.whole()} to ${d.max.whole()}$t")
            d.precipitationChance?.let { append(", ${it.whole()}% chance of precipitation") }
            d.precipitation?.takeIf { it > 0 }?.let { append(" (${"%.1f".format(Locale.ROOT, it)} ${units.precipitation})") }
            d.maxWind?.let { append(", wind up to ${it.whole()} ${units.wind}") }
            d.gusts?.let { append(", gusts up to ${it.whole()} ${units.wind}") }
            d.sunrise?.let { append(", sunrise ${it.timePart()}") }
            d.sunset?.let { append(", sunset ${it.timePart()}") }
            append(".\n")
        }
    }.trimEnd()

    private fun dayName(date: String, today: LocalDate?): String {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        return when (today?.let { day.toEpochDay() - it.toEpochDay() }) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        } + " (${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)})"
    }
}

private fun Double?.whole(): String = this?.roundToInt()?.toString() ?: "?"

/** "2026-10-06T11:30" → "11:30". */
private fun String.timePart(): String = substringAfter('T', this)
