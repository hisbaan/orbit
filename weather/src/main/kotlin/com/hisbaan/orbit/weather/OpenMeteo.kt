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
import kotlinx.serialization.json.jsonObject

/**
 * Weather from Open-Meteo: free, no key or account, global coverage. The default provider,
 * and the geocoder for every provider (place names to coordinates).
 */
class OpenMeteo(private val client: HttpClient) : WeatherProvider {
    override val name = "Open-Meteo"

    /** The best match for a place name, or null. */
    suspend fun geocode(name: String, language: String = "en"): Place? {
        val response = client.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", name)
            parameter("count", 1)
            parameter("language", language)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Geocoding failed: HTTP ${response.status.value}")
        return parsePlace(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    override suspend fun forecast(latitude: Double, longitude: Double, days: Int, imperial: Boolean, hours: Int): Forecast {
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", coordinate(latitude))
            parameter("longitude", coordinate(longitude))
            parameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m")
            parameter("hourly", "temperature_2m,precipitation_probability,weather_code,wind_speed_10m")
            parameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset",
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
        return parseForecast(Json.parseToJsonElement(response.bodyAsText()).jsonObject, imperial)
    }

    companion object {
        fun parsePlace(root: JsonObject): Place? {
            val first = root.objects("results").firstOrNull() ?: return null
            return Place(
                name = first.str("name") ?: return null,
                region = first.str("admin1"),
                country = first.str("country"),
                latitude = first.num("latitude") ?: return null,
                longitude = first.num("longitude") ?: return null,
            )
        }

        fun parseForecast(root: JsonObject, imperial: Boolean): Forecast {
            val current = root.obj("current")
            val hourly = root.obj("hourly")
            val daily = root.obj("daily")
            return Forecast(
                current = Current(
                    time = current?.str("time").orEmpty(),
                    temperature = current?.num("temperature_2m"),
                    feelsLike = current?.num("apparent_temperature"),
                    humidity = current?.num("relative_humidity_2m"),
                    condition = wmo(current?.num("weather_code")?.toInt()),
                    wind = current?.num("wind_speed_10m"),
                    gusts = current?.num("wind_gusts_10m"),
                ),
                hours = hourly.column("time").indices.map { i ->
                    Hour(
                        time = hourly.column("time")[i].str().orEmpty(),
                        temperature = hourly.column("temperature_2m").getOrNull(i).num(),
                        precipitationChance = hourly.column("precipitation_probability").getOrNull(i).num(),
                        condition = wmo(hourly.column("weather_code").getOrNull(i).num()?.toInt()),
                        wind = hourly.column("wind_speed_10m").getOrNull(i).num(),
                    )
                },
                days = daily.column("time").indices.map { i ->
                    Day(
                        date = daily.column("time")[i].str().orEmpty(),
                        condition = wmo(daily.column("weather_code").getOrNull(i).num()?.toInt()),
                        max = daily.column("temperature_2m_max").getOrNull(i).num(),
                        min = daily.column("temperature_2m_min").getOrNull(i).num(),
                        precipitationChance = daily.column("precipitation_probability_max").getOrNull(i).num(),
                        precipitation = daily.column("precipitation_sum").getOrNull(i).num(),
                        maxWind = daily.column("wind_speed_10m_max").getOrNull(i).num(),
                        gusts = daily.column("wind_gusts_10m_max").getOrNull(i).num(),
                        sunrise = daily.column("sunrise").getOrNull(i).str(),
                        sunset = daily.column("sunset").getOrNull(i).str(),
                    )
                },
                // The units asked for, in the providers' shared wording (Open-Meteo labels its own "mp/h", "inch").
                units = if (imperial) Units.IMPERIAL else Units.METRIC,
            )
        }

        private fun JsonObject?.column(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()
        private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
    }
}

/** WMO weather interpretation codes, as Open-Meteo reports them. */
internal fun wmo(code: Int?): Condition = when (code) {
    0 -> Condition(Sky.CLEAR, "clear")
    1 -> Condition(Sky.CLEAR, "mainly clear")
    2 -> Condition(Sky.PARTLY_CLOUDY, "partly cloudy")
    3 -> Condition(Sky.CLOUDY, "overcast")
    45, 48 -> Condition(Sky.FOG, "fog")
    51 -> Condition(Sky.DRIZZLE, "light drizzle")
    53 -> Condition(Sky.DRIZZLE, "drizzle")
    55 -> Condition(Sky.DRIZZLE, "heavy drizzle")
    56, 57 -> Condition(Sky.SLEET, "freezing drizzle")
    61 -> Condition(Sky.RAIN, "light rain")
    63 -> Condition(Sky.RAIN, "rain")
    65 -> Condition(Sky.RAIN, "heavy rain")
    66, 67 -> Condition(Sky.SLEET, "freezing rain")
    71 -> Condition(Sky.SNOW, "light snow")
    73 -> Condition(Sky.SNOW, "snow")
    75 -> Condition(Sky.SNOW, "heavy snow")
    77 -> Condition(Sky.SNOW, "snow grains")
    80 -> Condition(Sky.RAIN, "light showers")
    81 -> Condition(Sky.RAIN, "showers")
    82 -> Condition(Sky.RAIN, "violent showers")
    85, 86 -> Condition(Sky.SNOW, "snow showers")
    95 -> Condition(Sky.THUNDERSTORM, "thunderstorm")
    96, 99 -> Condition(Sky.THUNDERSTORM, "thunderstorm with hail")
    null -> Condition.UNKNOWN
    else -> Condition(Sky.UNKNOWN, "code $code")
}
