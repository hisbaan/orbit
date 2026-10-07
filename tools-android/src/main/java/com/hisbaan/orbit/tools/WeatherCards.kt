package com.hisbaan.orbit.tools

import android.content.Context
import com.hisbaan.orbit.agent.Card
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.providers.ToolSpec
import com.hisbaan.orbit.weather.Forecast
import com.hisbaan.orbit.weather.Sky
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The forecast get_weather fetched last, kept so show_weather_card can draw it: the card takes
 * its numbers from here rather than from the model, which would have to copy every hour.
 */
class LatestForecast {
    data class Snapshot(
        val forecast: Forecast,
        /** Short, for the card: "Toronto". */
        val place: String,
        val latitude: Double,
        val longitude: Double,
        val provider: String,
        val fetchedAt: Long,
    )

    @Volatile
    var snapshot: Snapshot? = null
}

/** Shows the latest forecast as a card. Confirming: the spoken answer is its confirmation. */
class ShowWeatherCardTool(private val context: Context, private val latest: LatestForecast) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "show_weather_card",
        description = "Show the forecast get_weather just fetched as a card on screen: now, the next 24 hours and the " +
            "week. Call it after get_weather, with your spoken answer as the confirmation.",
        parameters = objectSchema(),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val snapshot = latest.snapshot?.takeIf { System.currentTimeMillis() - it.fetchedAt < MAX_AGE_MS }
            ?: return ToolOutcome("Error: no recent forecast. Call get_weather first.")
        return ToolOutcome("Shown.", done = true, cards = listOf(card(snapshot, link(snapshot))))
    }

    /** Merry Sky for Pirate Weather (its own site), else the phone's weather app, else a web search. */
    private fun link(snapshot: LatestForecast.Snapshot): String {
        if (snapshot.provider == "Pirate Weather") return "https://merrysky.net/forecast/${snapshot.latitude},${snapshot.longitude}"
        if (context.packageManager.getLaunchIntentForPackage(WEATHER_APP) != null) return "app:$WEATHER_APP"
        return "https://www.google.com/search?q=" + URLEncoder.encode("weather ${snapshot.place}", "UTF-8")
    }

    companion object {
        private const val MAX_AGE_MS = 10 * 60_000L
        private const val WEATHER_APP = "com.google.android.apps.weather"

        /** The card for [snapshot]: now, 24 hours ("Now" first), the week ("Today", "Tomorrow", weekdays). */
        fun card(snapshot: LatestForecast.Snapshot, link: String?): Card.Weather {
            val f = snapshot.forecast
            val today = f.days.firstOrNull()
            return Card.Weather(
                place = snapshot.place,
                temperature = f.current.temperature,
                feelsLike = f.current.feelsLike,
                condition = f.current.condition.text.replaceFirstChar { it.uppercase() },
                sky = sky(f.current.condition.sky, f.current.time, f),
                high = today?.max,
                low = today?.min,
                unit = f.units.temperature,
                hours = f.hours.take(24).mapIndexed { i, h ->
                    if (i == 0) {
                        // The first hour is the forecast for the top of this hour (8:00 at 8:46):
                        // show current conditions instead, so "Now" agrees with the top of the card.
                        Card.HourSlot("Now", f.current.temperature ?: h.temperature, sky(f.current.condition.sky, f.current.time, f), h.precipitationChance?.toInt())
                    } else {
                        Card.HourSlot(h.time.substringAfter('T'), h.temperature, sky(h.condition.sky, h.time, f), h.precipitationChance?.toInt())
                    }
                },
                days = f.days.take(7).mapIndexed { i, d ->
                    Card.DaySlot(dayLabel(i, d.date), d.max, d.min, sky(d.condition.sky, null, f), d.precipitationChance?.toInt())
                },
                link = link,
            )
        }

        /** The card's icon name; clear and partly cloudy skies get night versions between sunset and sunrise. */
        private fun sky(sky: Sky, time: String?, forecast: Forecast): String {
            val name = sky.name.lowercase()
            if (time == null || (sky != Sky.CLEAR && sky != Sky.PARTLY_CLOUDY)) return name
            val at = runCatching { LocalDateTime.parse(time) }.getOrNull() ?: return name
            val day = forecast.days.firstOrNull { it.date == at.toLocalDate().toString() } ?: return name
            val sunrise = day.sunrise?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return name
            val sunset = day.sunset?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return name
            return if (at.isBefore(sunrise) || !at.isBefore(sunset)) "${name}_night" else name
        }

        private fun dayLabel(index: Int, date: String): String = when (index) {
            0 -> "Today"
            1 -> "Tomorrow"
            // Some locales (en-CA) abbreviate with a period: "Thu."
            else -> runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).removeSuffix(".") }
                .getOrDefault(date)
        }
    }
}
