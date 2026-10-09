package com.hisbaan.orbit.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures follow each provider's documented response format. */
class ProvidersTest {
    private fun fixture(name: String) =
        Json.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject

    @Test
    fun `pirate weather in local time, from the current hour`() {
        val forecast = PirateWeather.parse(fixture("pirate_toronto.json"), days = 2, hours = 12, imperial = false)
        val lines = forecast.describe("Toronto").lines()
        assertEquals("Weather for Toronto (local time 11:30).", lines[0])
        assertEquals("Now: partly cloudy, 11°C (feels like 8°C), wind 11 km/h gusting 21 km/h, humidity 46%.", lines[1])
        assertEquals(
            "Next hours: 11:00 11°C partly cloudy, precipitation 5%; 12:00 12°C light rain, precipitation 60%; " +
                "13:00 13°C drizzle, precipitation 40%.",
            lines[2],
        )
        assertEquals(
            // The calendar day's extremes, and gusts rather than the day's average wind as a maximum.
            "Today (6 Oct): rain in the afternoon, 2 to 16°C, 70% chance of precipitation, gusts up to 41 km/h, sunrise 07:21, sunset 18:49.",
            lines[3],
        )
        assertEquals("Tomorrow (7 Oct): clear throughout the day, 6 to 17°C, 0% chance of precipitation, gusts up to 18 km/h", lines[4].substringBefore(", sunrise"))
        assertEquals(2, forecast.days.size)
        assertEquals(Sky.PARTLY_CLOUDY, forecast.current.condition.sky)
        assertEquals(Sky.DRIZZLE, forecast.hours[2].condition.sky)
    }

    @Test
    fun `google weather from its three lookups`() {
        val forecast = GoogleWeather.parse(fixture("google_current.json"), fixture("google_hours.json"), fixture("google_days.json"), imperial = false)
        val lines = forecast.describe("Toronto").lines()
        assertEquals("Weather for Toronto (local time 11:30).", lines[0])
        assertEquals("Now: mostly sunny, 11°C (feels like 8°C), wind 11 km/h gusting 21 km/h, humidity 46%.", lines[1])
        assertEquals("Next hours: 11:00 11°C mostly sunny, precipitation 5%; 12:00 12°C light rain showers, precipitation 60%.", lines[2])
        assertEquals(
            "Today (6 Oct): showers, 3 to 15°C, 70% chance of precipitation, wind up to 22 km/h, sunrise 07:21, sunset 18:49.",
            lines[3],
        )
        assertEquals("Tomorrow (7 Oct): heavy snow storm, -3 to 1°C, 90% chance of precipitation.", lines[4])
        assertEquals(Sky.CLEAR, forecast.current.condition.sky)
        assertEquals(Sky.SNOW, forecast.days[1].condition.sky)
    }
}
