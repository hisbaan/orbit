package com.hisbaan.orbit.tools

import com.hisbaan.orbit.agent.Card
import com.hisbaan.orbit.weather.Condition
import com.hisbaan.orbit.weather.Current
import com.hisbaan.orbit.weather.Day
import com.hisbaan.orbit.weather.Forecast
import com.hisbaan.orbit.weather.Hour
import com.hisbaan.orbit.weather.Sky
import com.hisbaan.orbit.weather.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCardTest {
    private val clear = Condition(Sky.CLEAR, "clear")

    private fun day(date: String, rise: String, set: String) =
        Day(date, Condition(Sky.RAIN, "rain"), 15.0, 3.0, 70.0, null, null, "${date}T$rise", "${date}T$set")

    @Test
    fun `builds the card from the forecast, with night icons and day names`() {
        val hours = (0 until 30).map { i ->
            val t = java.time.LocalDateTime.parse("2026-10-06T17:00").plusHours(i.toLong())
            Hour(t.toString(), 10.0 + i, if (i % 2 == 0) 40.0 else null, clear, null)
        }
        val forecast = Forecast(
            current = Current("2026-10-06T17:30", 11.4, 8.2, 46.0, clear, null, null),
            hours = hours,
            days = listOf(day("2026-10-06", "07:21", "18:49"), day("2026-10-07", "07:22", "18:47"), day("2026-10-08", "07:23", "18:45")),
            units = Units.METRIC,
        )
        val card = ShowWeatherCardTool.card(LatestForecast.Snapshot(forecast, "Toronto", 43.7, -79.4, "Open-Meteo", 0), "app:x")

        assertEquals("Clear", card.condition)
        assertEquals("clear", card.sky) // 17:30 is before sunset
        assertEquals(24, card.hours.size)
        assertEquals(Card.HourSlot("Now", 11.4, "clear", 40), card.hours[0]) // current conditions, not the 17:00 forecast
        assertEquals("18:00", card.hours[1].label)
        assertEquals("clear", card.hours[1].sky)
        assertEquals("clear_night", card.hours[2].sky) // 19:00, after sunset
        assertEquals("clear", card.hours[15].sky) // 08:00 the next day
        assertEquals(listOf("Today", "Tomorrow", "Thu"), card.days.map { it.label })
        assertEquals(15.0, card.high)
        assertEquals("app:x", card.link)
    }
}
