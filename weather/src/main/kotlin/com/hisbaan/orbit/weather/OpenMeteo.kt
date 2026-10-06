package com.hisbaan.orbit.weather

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

data class Place(val name: String, val region: String?, val country: String?, val latitude: Double, val longitude: Double) {
    val label: String get() = listOfNotNull(name, region?.takeIf { it != name }, country).joinToString(", ")
}

data class Units(val temperature: String, val wind: String, val precipitation: String)

data class Current(
    val time: String,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Double?,
    val code: Int?,
    val wind: Double?,
    val gusts: Double?,
)

data class Hour(val time: String, val temperature: Double?, val precipitationChance: Double?, val code: Int?, val wind: Double?)

data class Day(
    val date: String,
    val code: Int?,
    val max: Double?,
    val min: Double?,
    val precipitationChance: Double?,
    val precipitation: Double?,
    val maxWind: Double?,
    val sunrise: String?,
    val sunset: String?,
)

data class Forecast(val current: Current, val hours: List<Hour>, val days: List<Day>, val units: Units) {
    /** Plain text for the model: now, the next hours, then each day. Times are local to the place. */
    fun describe(place: String): String = buildString {
        val t = units.temperature
        append("Weather for $place (local time ${current.time.timePart()}).\n")
        append("Now: ${condition(current.code)}, ${current.temperature.whole()}$t")
        current.feelsLike?.let { append(" (feels like ${it.whole()}$t)") }
        current.wind?.let { append(", wind ${it.whole()} ${units.wind}") }
        current.gusts?.let { append(" gusting ${it.whole()} ${units.wind}") }
        current.humidity?.let { append(", humidity ${it.whole()}%") }
        append(".\n")
        if (hours.isNotEmpty()) {
            append("Next hours: ")
            append(
                hours.joinToString("; ") { h ->
                    "${h.time.timePart()} ${h.temperature.whole()}$t ${condition(h.code)}" +
                        (h.precipitationChance?.let { ", precipitation ${it.whole()}%" } ?: "")
                },
            )
            append(".\n")
        }
        val today = current.time.take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (d in days) {
            append("${dayName(d.date, today)}: ${condition(d.code)}, ${d.min.whole()} to ${d.max.whole()}$t")
            d.precipitationChance?.let { append(", ${it.whole()}% chance of precipitation") }
            d.precipitation?.takeIf { it > 0 }?.let { append(" (${"%.1f".format(Locale.ROOT, it)} ${units.precipitation})") }
            d.maxWind?.let { append(", wind up to ${it.whole()} ${units.wind}") }
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

/**
 * Weather from Open-Meteo: free, no key or account, global coverage. Android has no API
 * for the phone's own weather app, so this is the source.
 */
class OpenMeteo(private val client: HttpClient) {
    /** The best match for a place name, or null. */
    suspend fun geocode(name: String, language: String = "en"): Place? {
        val response = client.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", name)
            parameter("count", 1)
            parameter("language", language)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Geocoding failed: HTTP ${response.status.value}")
        return parsePlace(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    /** Current conditions, the next [hours] hours and [days] days (including today). */
    suspend fun forecast(latitude: Double, longitude: Double, days: Int, imperial: Boolean, hours: Int = 12): Forecast {
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m")
            parameter("hourly", "temperature_2m,precipitation_probability,weather_code,wind_speed_10m")
            parameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,sunrise,sunset",
            )
            parameter("timezone", "auto")
            parameter("forecast_days", days.coerceIn(1, 16))
            parameter("forecast_hours", hours)
            if (imperial) {
                parameter("temperature_unit", "fahrenheit")
                parameter("wind_speed_unit", "mph")
                parameter("precipitation_unit", "inch")
            }
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Forecast failed: HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        return parseForecast(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parsePlace(root: JsonObject): Place? {
            val first = root["results"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
            return Place(
                name = first.str("name") ?: return null,
                region = first.str("admin1"),
                country = first.str("country"),
                latitude = first.num("latitude") ?: return null,
                longitude = first.num("longitude") ?: return null,
            )
        }

        fun parseForecast(root: JsonObject): Forecast {
            val current = root.obj("current")
            val hourly = root.obj("hourly")
            val daily = root.obj("daily")
            val units = root.obj("current_units")
            val dailyUnits = root.obj("daily_units")
            return Forecast(
                current = Current(
                    time = current?.str("time").orEmpty(),
                    temperature = current?.num("temperature_2m"),
                    feelsLike = current?.num("apparent_temperature"),
                    humidity = current?.num("relative_humidity_2m"),
                    code = current?.num("weather_code")?.toInt(),
                    wind = current?.num("wind_speed_10m"),
                    gusts = current?.num("wind_gusts_10m"),
                ),
                hours = hourly.column("time").indices.map { i ->
                    Hour(
                        time = hourly.column("time")[i].str().orEmpty(),
                        temperature = hourly.column("temperature_2m").getOrNull(i).num(),
                        precipitationChance = hourly.column("precipitation_probability").getOrNull(i).num(),
                        code = hourly.column("weather_code").getOrNull(i).num()?.toInt(),
                        wind = hourly.column("wind_speed_10m").getOrNull(i).num(),
                    )
                },
                days = daily.column("time").indices.map { i ->
                    Day(
                        date = daily.column("time")[i].str().orEmpty(),
                        code = daily.column("weather_code").getOrNull(i).num()?.toInt(),
                        max = daily.column("temperature_2m_max").getOrNull(i).num(),
                        min = daily.column("temperature_2m_min").getOrNull(i).num(),
                        precipitationChance = daily.column("precipitation_probability_max").getOrNull(i).num(),
                        precipitation = daily.column("precipitation_sum").getOrNull(i).num(),
                        maxWind = daily.column("wind_speed_10m_max").getOrNull(i).num(),
                        sunrise = daily.column("sunrise").getOrNull(i).str(),
                        sunset = daily.column("sunset").getOrNull(i).str(),
                    )
                },
                units = Units(
                    temperature = units?.str("temperature_2m") ?: "°C",
                    wind = units?.str("wind_speed_10m") ?: "km/h",
                    precipitation = dailyUnits?.str("precipitation_sum") ?: "mm",
                ),
            )
        }

        private fun JsonObject.obj(key: String) = get(key) as? JsonObject
        private fun JsonObject?.column(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()
        private fun JsonObject.str(key: String) = get(key).str()
        private fun JsonObject.num(key: String) = get(key).num()
        private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
    }
}

/** WMO weather interpretation codes, as Open-Meteo reports them. */
fun condition(code: Int?): String = when (code) {
    0 -> "clear"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51 -> "light drizzle"
    53 -> "drizzle"
    55 -> "heavy drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71 -> "light snow"
    73 -> "snow"
    75 -> "heavy snow"
    77 -> "snow grains"
    80 -> "light showers"
    81 -> "showers"
    82 -> "violent showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    null -> "unknown"
    else -> "code $code"
}

private fun Double?.whole(): String = this?.roundToInt()?.toString() ?: "?"

/** "2026-10-06T11:30" → "11:30". */
private fun String.timePart(): String = substringAfter('T', this)
