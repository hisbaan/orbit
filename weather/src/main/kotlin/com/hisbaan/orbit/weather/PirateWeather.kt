package com.hisbaan.orbit.weather

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pirate Weather (pirateweather.net), the source behind Merry Sky: a Dark Sky-style API over
 * national weather models, including the high-resolution HRRR (US) and HRDPS (Canada).
 * Needs a free API key.
 */
class PirateWeather(private val client: HttpClient, private val apiKey: String) : WeatherProvider {
    override val name = "Pirate Weather"

    override suspend fun forecast(latitude: Double, longitude: Double, days: Int, imperial: Boolean, hours: Int): Forecast {
        val response = try {
            client.get("https://api.pirateweather.net/forecast/$apiKey/$latitude,$longitude") {
                // "ca": °C and km/h, as Open-Meteo's metric; "us": °F and mph.
                parameter("units", if (imperial) "us" else "ca")
                parameter("exclude", "minutely,alerts")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The key is in the URL path (the API takes it nowhere else), and request errors quote
            // the URL; their messages reach the log and the model.
            throw IOException("Pirate Weather request failed: ${e::class.simpleName}: ${e.message.orEmpty().replace(apiKey, "<key>")}")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Pirate Weather failed: HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        return parse(json.parseToJsonElement(response.bodyAsText()).jsonObject, days, hours, imperial)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val localDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun parse(root: JsonObject, days: Int, hours: Int, imperial: Boolean): Forecast {
            val zone = root.str("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
            fun time(seconds: Long?) = seconds?.let { localTime.format(Instant.ofEpochSecond(it).atZone(zone)) }
            fun date(seconds: Long?) = seconds?.let { localDate.format(Instant.ofEpochSecond(it).atZone(zone)) }
            val now = root.obj("currently")
            val nowTime = now?.long("time")
            return Forecast(
                current = Current(
                    time = time(nowTime).orEmpty(),
                    temperature = now?.num("temperature"),
                    feelsLike = now?.num("apparentTemperature"),
                    humidity = now?.num("humidity")?.let { it * 100 },
                    condition = condition(now),
                    wind = now?.num("windSpeed"),
                    gusts = now?.num("windGust"),
                ),
                // Hourly data starts at the top of the current hour.
                hours = root.obj("hourly").data()
                    .filter { (it.long("time") ?: 0) + 3600 > (nowTime ?: 0) }
                    .take(hours)
                    .map { h ->
                        Hour(
                            time = time(h.long("time")).orEmpty(),
                            temperature = h.num("temperature"),
                            precipitationChance = h.num("precipProbability")?.let { it * 100 },
                            condition = condition(h),
                            wind = h.num("windSpeed"),
                        )
                    },
                days = root.obj("daily").data().take(days).map { d ->
                    Day(
                        date = date(d.long("time")).orEmpty(),
                        condition = condition(d),
                        max = d.num("temperatureHigh") ?: d.num("temperatureMax"),
                        min = d.num("temperatureLow") ?: d.num("temperatureMin"),
                        precipitationChance = d.num("precipProbability")?.let { it * 100 },
                        precipitation = null,
                        maxWind = d.num("windSpeed"),
                        sunrise = time(d.long("sunriseTime")),
                        sunset = time(d.long("sunsetTime")),
                    )
                },
                units = if (imperial) Units.IMPERIAL else Units.METRIC,
            )
        }

        /** Dark Sky icon names, plus Pirate Weather's thunderstorm and hail. */
        private fun condition(block: JsonObject?): Condition {
            val text = block?.str("summary")?.lowercase() ?: return Condition.UNKNOWN
            val sky = when (block.str("icon")) {
                "clear-day", "clear-night" -> Sky.CLEAR
                "partly-cloudy-day", "partly-cloudy-night" -> Sky.PARTLY_CLOUDY
                "cloudy" -> Sky.CLOUDY
                "fog" -> Sky.FOG
                "rain" -> if ("drizzle" in text) Sky.DRIZZLE else Sky.RAIN
                "snow" -> Sky.SNOW
                "sleet", "hail", "mixed" -> Sky.SLEET
                "wind" -> Sky.WIND
                "thunderstorm" -> Sky.THUNDERSTORM
                else -> Sky.UNKNOWN
            }
            return Condition(sky, text)
        }

        private fun JsonObject.obj(key: String) = get(key) as? JsonObject
        private fun JsonObject?.data(): List<JsonObject> = (this?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: num(key)?.toLong()
    }
}
