package com.hisbaan.orbit.weather

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Google Maps Platform Weather API: the data behind Google's own weather app, so answers match
 * the phone's weather. Needs a Google Cloud API key with the Weather API enabled (billed per
 * request beyond Google's free usage).
 */
class GoogleWeather(private val client: HttpClient, private val apiKey: String) : WeatherProvider {
    override val name = "Google Weather"

    override suspend fun forecast(latitude: Double, longitude: Double, days: Int, imperial: Boolean, hours: Int): Forecast = coroutineScope {
        val current = async { lookup("currentConditions:lookup", latitude, longitude, imperial) }
        val hourly = async { lookup("forecast/hours:lookup", latitude, longitude, imperial, "hours" to hours, "pageSize" to hours) }
        val daily = async { lookup("forecast/days:lookup", latitude, longitude, imperial, "days" to days, "pageSize" to days) }
        parse(current.await(), hourly.await(), daily.await(), imperial)
    }

    private suspend fun lookup(path: String, latitude: Double, longitude: Double, imperial: Boolean, vararg extra: Pair<String, Int>): JsonObject {
        val response = client.get("https://weather.googleapis.com/v1/$path") {
            // In a header, not the URL: request errors quote the URL, and those get logged.
            header("X-Goog-Api-Key", apiKey)
            parameter("location.latitude", latitude)
            parameter("location.longitude", longitude)
            parameter("unitsSystem", if (imperial) "IMPERIAL" else "METRIC")
            parameter("languageCode", Locale.getDefault().toLanguageTag())
            extra.forEach { (k, v) -> parameter(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Google Weather failed: HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        fun parse(current: JsonObject, hourly: JsonObject, daily: JsonObject, imperial: Boolean): Forecast {
            val zone = (current.obj("timeZone") ?: hourly.obj("timeZone") ?: daily.obj("timeZone"))?.str("id")
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
            fun local(iso: String?) = iso?.let { runCatching { localTime.format(Instant.parse(it).atZone(zone)) }.getOrNull() }
            return Forecast(
                current = Current(
                    time = local(current.str("currentTime")).orEmpty(),
                    temperature = current.obj("temperature")?.num("degrees"),
                    feelsLike = current.obj("feelsLikeTemperature")?.num("degrees"),
                    humidity = current.num("relativeHumidity"),
                    condition = condition(current.obj("weatherCondition")),
                    wind = current.obj("wind")?.obj("speed")?.num("value"),
                    gusts = current.obj("wind")?.obj("gust")?.num("value"),
                ),
                hours = hourly.list("forecastHours").map { h ->
                    Hour(
                        time = local(h.obj("interval")?.str("startTime")).orEmpty(),
                        temperature = h.obj("temperature")?.num("degrees"),
                        precipitationChance = h.obj("precipitation")?.obj("probability")?.num("percent"),
                        condition = condition(h.obj("weatherCondition")),
                        wind = h.obj("wind")?.obj("speed")?.num("value"),
                    )
                },
                days = daily.list("forecastDays").map { d ->
                    val date = d.obj("displayDate")
                    val day = d.obj("daytimeForecast")
                    val night = d.obj("nighttimeForecast")
                    Day(
                        date = date?.let { "%04d-%02d-%02d".format(it.int("year"), it.int("month"), it.int("day")) }.orEmpty(),
                        condition = condition(day?.obj("weatherCondition")),
                        max = d.obj("maxTemperature")?.num("degrees"),
                        min = d.obj("minTemperature")?.num("degrees"),
                        precipitationChance = listOfNotNull(day, night)
                            .mapNotNull { it.obj("precipitation")?.obj("probability")?.num("percent") }.maxOrNull(),
                        precipitation = null,
                        maxWind = listOfNotNull(day, night).mapNotNull { it.obj("wind")?.obj("speed")?.num("value") }.maxOrNull(),
                        sunrise = local(d.obj("sunEvents")?.str("sunriseTime")),
                        sunset = local(d.obj("sunEvents")?.str("sunsetTime")),
                    )
                },
                units = if (imperial) Units.IMPERIAL else Units.METRIC,
            )
        }

        /** Google's condition types (CLEAR, LIGHT_RAIN_SHOWERS, SNOWSTORM...) onto [Sky]. */
        private fun condition(block: JsonObject?): Condition {
            block ?: return Condition.UNKNOWN
            val type = block.str("type").orEmpty()
            val text = block.obj("description")?.str("text")?.lowercase() ?: type.lowercase().replace('_', ' ')
            val sky = when {
                type == "CLEAR" || type == "MOSTLY_CLEAR" -> Sky.CLEAR
                type == "PARTLY_CLOUDY" -> Sky.PARTLY_CLOUDY
                type == "MOSTLY_CLOUDY" || type == "CLOUDY" -> Sky.CLOUDY
                "THUNDER" in type -> Sky.THUNDERSTORM
                "HAIL" in type || type == "RAIN_AND_SNOW" -> Sky.SLEET
                "SNOW" in type -> Sky.SNOW
                "RAIN" in type || "SHOWER" in type -> Sky.RAIN
                type == "WINDY" -> Sky.WIND
                "FOG" in type || "HAZE" in type -> Sky.FOG
                else -> Sky.UNKNOWN
            }
            return Condition(sky, text)
        }

        private fun JsonObject.obj(key: String) = get(key) as? JsonObject
        private fun JsonObject.list(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
    }
}
