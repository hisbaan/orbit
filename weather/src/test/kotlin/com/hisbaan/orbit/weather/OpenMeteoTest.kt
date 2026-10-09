package com.hisbaan.orbit.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {
    private fun fixture(name: String) =
        Json.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject

    @Test
    fun `parses a place`() {
        val place = OpenMeteo.parsePlace(fixture("geocode_toronto.json"))!!
        assertEquals("Toronto, Ontario, Canada", place.label)
        assertEquals(43.7, place.latitude, 0.1)
    }

    @Test
    fun `describes current, hourly and daily weather`() {
        val forecast = OpenMeteo.parseForecast(fixture("forecast_toronto.json"), imperial = false)
        assertEquals(12, forecast.hours.size)
        assertEquals(3, forecast.days.size)

        val text = forecast.describe("Toronto")
        val lines = text.lines()
        assertEquals("Weather for Toronto (local time 11:30).", lines[0])
        assertEquals("Now: clear, 11°C (feels like 8°C), wind 11 km/h gusting 21 km/h, humidity 46%.", lines[1])
        assertTrue(lines[2], lines[2].startsWith("Next hours: 11:00 11°C clear, precipitation 0%; 12:00"))
        assertEquals(
            "Today (6 Oct): overcast, 3 to 15°C, 1% chance of precipitation, wind up to 22 km/h, sunrise 07:21, sunset 18:49.",
            lines[3],
        )
        assertTrue(lines[4], lines[4].startsWith("Tomorrow (7 Oct): "))
        assertTrue(lines[5], lines[5].startsWith("Thursday (8 Oct): "))
    }

    @Test
    fun `imperial units read as words the others use`() {
        // Open-Meteo labels its own "mp/h" and "inch".
        assertEquals(Units.IMPERIAL, OpenMeteo.parseForecast(fixture("forecast_toronto.json"), imperial = true).units)
    }

    @Test
    fun `coordinates near zero are plain decimals`() {
        assertEquals("0.00010", coordinate(0.0001))
        assertEquals("-79.38320", coordinate(-79.3832))
    }
}
