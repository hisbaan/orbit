package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarFormatTest {
    private val toronto = ZoneId.of("America/Toronto")

    @Test
    fun `timed events default to an hour in local time`() {
        val span = CalendarFormat.span("2026-10-08T14:00", null, null, null, toronto).getOrThrow()
        assertEquals(Instant.parse("2026-10-08T18:00:00Z"), span.begin)
        assertEquals(Instant.parse("2026-10-08T19:00:00Z"), span.end)
        assertEquals("Thu 8 Oct 14:00-15:00", CalendarFormat.describeTime(span.begin, span.end, span.allDay, toronto))
        val withDuration = CalendarFormat.span("2026-10-08 09:30", null, 45, null, toronto).getOrThrow()
        assertEquals("Thu 8 Oct 09:30-10:15", CalendarFormat.describeTime(withDuration.begin, withDuration.end, false, toronto))
    }

    @Test
    fun `a date alone is an all-day event stored as UTC midnights`() {
        val one = CalendarFormat.span("2026-10-08", null, null, null, toronto).getOrThrow()
        assertTrue(one.allDay)
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), one.begin)
        assertEquals(Instant.parse("2026-10-09T00:00:00Z"), one.end)
        assertEquals("Thu 8 Oct, all day", CalendarFormat.describeTime(one.begin, one.end, true, toronto))
        val three = CalendarFormat.span("2026-10-08", "2026-10-10", null, null, toronto).getOrThrow()
        assertEquals("Thu 8 Oct to Sat 10 Oct, all day", CalendarFormat.describeTime(three.begin, three.end, true, toronto))
    }

    @Test
    fun `rejects bad input`() {
        assertTrue(CalendarFormat.span("tomorrow at 2", null, null, null, toronto).isFailure)
        assertTrue(CalendarFormat.span("2026-10-08T14:00", "2026-10-08T13:00", null, null, toronto).isFailure)
    }

    @Test
    fun `describes events for the model`() {
        val event = CalendarFormat.Event(
            id = 42, title = "Dentist", begin = Instant.parse("2026-10-08T18:00:00Z"), end = Instant.parse("2026-10-08T19:00:00Z"),
            allDay = false, location = "123 Main St", calendar = "Personal", recurring = true,
        )
        assertEquals("- Thu 8 Oct 14:00-15:00: Dentist, at 123 Main St (Personal, repeats) [id 42]", CalendarFormat.line(event, toronto))
    }

    @Test
    fun `picks the named, then the chosen default, then primary, then any writable calendar`() {
        val calendars = listOf(
            CalendarAccess.Calendar(1, "Holidays", "a", primary = false, writable = false),
            CalendarAccess.Calendar(2, "Work", "a", primary = false, writable = true),
            CalendarAccess.Calendar(3, "me@example.com", "a", primary = true, writable = true),
            CalendarAccess.Calendar(4, "other@example.com", "b", primary = true, writable = true),
        )
        assertEquals(3L, CalendarAccess.pick(calendars, null)?.id)
        assertEquals(4L, CalendarAccess.pick(calendars, null, defaultId = 4)?.id)
        assertEquals(3L, CalendarAccess.pick(calendars, null, defaultId = 99)?.id)
        assertEquals(2L, CalendarAccess.pick(calendars, "work", defaultId = 4)?.id)
        assertEquals(2L, CalendarAccess.pick(calendars, "work")?.id)
        assertEquals(null, CalendarAccess.pick(calendars, "Holidays"))
    }

    @Test
    fun `all-day events count on their own date, not the neighbouring one`() {
        fun allDay(date: String) = CalendarFormat.span(date, null, null, null, toronto).getOrThrow()
            .let { CalendarFormat.Event(1, "Holiday", it.begin, it.end, allDay = true, location = null, calendar = "c") }
        // "Today" in Toronto: 04:00Z to 04:00Z, which also covers tomorrow's first UTC hours.
        val today = Instant.parse("2026-10-08T04:00:00Z")
        val tomorrow = Instant.parse("2026-10-09T04:00:00Z")
        assertTrue(CalendarFormat.inRange(allDay("2026-10-08"), today, tomorrow, toronto))
        assertFalse(CalendarFormat.inRange(allDay("2026-10-09"), today, tomorrow, toronto))
        assertFalse(CalendarFormat.inRange(allDay("2026-10-07"), today, tomorrow, toronto))
        // East of UTC the overlap is with yesterday instead.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertTrue(CalendarFormat.inRange(allDay("2026-10-08"), Instant.parse("2026-10-07T15:00:00Z"), Instant.parse("2026-10-08T15:00:00Z"), tokyo))
        assertFalse(CalendarFormat.inRange(allDay("2026-10-07"), Instant.parse("2026-10-07T15:00:00Z"), Instant.parse("2026-10-08T15:00:00Z"), tokyo))
        // Timed events: plain overlap.
        val meeting = CalendarFormat.Event(2, "Standup", Instant.parse("2026-10-09T13:00:00Z"), Instant.parse("2026-10-09T13:15:00Z"), false, null, "c")
        assertFalse(CalendarFormat.inRange(meeting, today, tomorrow, toronto))
    }
}
