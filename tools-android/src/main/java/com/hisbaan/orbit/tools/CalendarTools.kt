package com.hisbaan.orbit.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.agent.booleanProperty
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The phone's calendars through Android's calendar provider, which Google Calendar (and other
 * account calendars) sync to, so changes show up everywhere.
 */
class CalendarAccess(private val context: Context) {
    data class Calendar(val id: Long, val name: String, val account: String, val primary: Boolean, val writable: Boolean)

    val canRead: Boolean get() = granted(Manifest.permission.READ_CALENDAR)
    val canWrite: Boolean get() = granted(Manifest.permission.WRITE_CALENDAR)

    suspend fun calendars(): List<Calendar> = withContext(Dispatchers.IO) {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.IS_PRIMARY, Calendars.CALENDAR_ACCESS_LEVEL)
        context.contentResolver.query(Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Calendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) == 1, c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR))
                }
            }
        }.orEmpty()
    }

    /** Occurrences overlapping [from, to), recurring events expanded, in visible calendars. */
    suspend fun events(from: Instant, to: Instant): List<CalendarFormat.Event> = withContext(Dispatchers.IO) {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME, Instances.RRULE,
        )
        context.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarFormat.Event(
                            id = c.getLong(0),
                            title = c.getString(1).orEmpty().ifBlank { "(no title)" },
                            begin = Instant.ofEpochMilli(c.getLong(2)),
                            end = Instant.ofEpochMilli(c.getLong(3)),
                            allDay = c.getInt(4) == 1,
                            location = c.getString(5)?.takeIf { it.isNotBlank() },
                            calendar = c.getString(6).orEmpty(),
                            recurring = !c.getString(7).isNullOrBlank(),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** The event's own row (not an occurrence), or null if it doesn't exist. */
    suspend fun event(id: Long): CalendarFormat.Event? = withContext(Dispatchers.IO) {
        val projection = arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION, Events.CALENDAR_DISPLAY_NAME, Events.RRULE)
        context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            CalendarFormat.Event(
                id = id,
                title = c.getString(0).orEmpty(),
                begin = Instant.ofEpochMilli(c.getLong(1)),
                end = Instant.ofEpochMilli(if (c.isNull(2)) c.getLong(1) else c.getLong(2)),
                allDay = c.getInt(3) == 1,
                location = c.getString(4)?.takeIf { it.isNotBlank() },
                calendar = c.getString(5).orEmpty(),
                recurring = !c.getString(6).isNullOrBlank(),
            )
        }
    }

    /** Inserts an event; returns its id. All-day events are stored as UTC midnights, as the provider requires. */
    suspend fun insert(calendar: Calendar, title: String, span: CalendarFormat.Span, location: String?, description: String?): Long =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar.id)
                put(Events.TITLE, title)
                put(Events.DTSTART, span.begin.toEpochMilli())
                put(Events.DTEND, span.end.toEpochMilli())
                put(Events.ALL_DAY, if (span.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (span.allDay) "UTC" else span.zone.id)
                location?.let { put(Events.EVENT_LOCATION, it) }
                description?.let { put(Events.DESCRIPTION, it) }
            }
            val uri = context.contentResolver.insert(Events.CONTENT_URI, values) ?: error("the calendar provider rejected the event")
            ContentUris.parseId(uri)
        }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * The calendar [name] matches, or the one new events go to by default: the user's
         * choice ([defaultId]), then a primary one (there's one per account), then any writable.
         */
        fun pick(calendars: List<Calendar>, name: String?, defaultId: Long? = null): Calendar? {
            val writable = calendars.filter { it.writable }
            if (name != null) {
                return writable.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: writable.firstOrNull { it.name.contains(name, ignoreCase = true) }
            }
            return writable.firstOrNull { it.id == defaultId } ?: writable.firstOrNull { it.primary } ?: writable.firstOrNull()
        }
    }
}

/** Parsing the model's times and describing events; pure, so it's unit tested. */
object CalendarFormat {
    data class Event(
        val id: Long,
        val title: String,
        val begin: Instant,
        val end: Instant,
        val allDay: Boolean,
        val location: String?,
        val calendar: String,
        val recurring: Boolean = false,
    )

    data class Span(val begin: Instant, val end: Instant, val allDay: Boolean, val zone: ZoneId)

    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "2026-10-08T14:00", "2026-10-08 14:00" or "2026-10-08". A date alone means all day. */
    fun parse(text: String): Pair<LocalDateTime, Boolean>? {
        val t = text.trim().replace(' ', 'T').removeSuffix("Z")
        return try {
            if ('T' in t) LocalDateTime.parse(t) to false else LocalDate.parse(t).atStartOfDay() to true
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * The event's span from the model's [start] / [end] / [durationMinutes]. Timed events
     * default to an hour. For all-day events [end] is the last day; they're stored from UTC
     * midnight to the UTC midnight after the last day, as the provider requires.
     */
    fun span(start: String, end: String?, durationMinutes: Int?, allDay: Boolean?, zone: ZoneId): Result<Span> {
        val (begin, dateOnly) = parse(start) ?: return Result.failure(IllegalArgumentException("start '$start' isn't a date or date-time like 2026-10-08T14:00"))
        val whole = allDay ?: dateOnly
        val finish = end?.let { parse(it)?.first ?: return Result.failure(IllegalArgumentException("end '$end' isn't a date or date-time")) }
        if (whole) {
            val lastDay = (finish ?: begin).toLocalDate()
            if (lastDay.isBefore(begin.toLocalDate())) return Result.failure(IllegalArgumentException("end is before start"))
            return Result.success(
                Span(begin.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(), lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), true, zone),
            )
        }
        val stop = finish ?: begin.plusMinutes((durationMinutes ?: 60).toLong())
        if (!stop.isAfter(begin)) return Result.failure(IllegalArgumentException("end must be after start"))
        return Result.success(Span(begin.atZone(zone).toInstant(), stop.atZone(zone).toInstant(), false, zone))
    }

    /** "Thu 8 Oct 14:00-15:00" or "Thu 8 Oct, all day" / "Thu 8 Oct to Sat 10 Oct, all day". */
    fun describeTime(begin: Instant, end: Instant, allDay: Boolean, zone: ZoneId): String {
        if (allDay) {
            val first = begin.atZone(ZoneOffset.UTC).toLocalDate()
            val last = end.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).coerceAtLeast(first)
            return if (last == first) "${day.format(first)}, all day" else "${day.format(first)} to ${day.format(last)}, all day"
        }
        val b = begin.atZone(zone)
        val e = end.atZone(zone)
        val endText = if (e.toLocalDate() == b.toLocalDate()) time.format(e) else "${day.format(e)} ${time.format(e)}"
        return "${day.format(b)} ${time.format(b)}-$endText"
    }

    /** One line for the model. */
    fun line(event: Event, zone: ZoneId): String = buildString {
        append("- ${describeTime(event.begin, event.end, event.allDay, zone)}: ${event.title}")
        event.location?.let { append(", at $it") }
        append(" (${event.calendar}")
        if (event.recurring) append(", repeats")
        append(") [id ${event.id}]")
    }

    fun describeRange(from: Instant, to: Instant, zone: ZoneId): String {
        val f = from.atZone(zone)
        val t = to.atZone(zone)
        return "${day.format(f)} ${time.format(f)} to ${day.format(t)} ${time.format(t)} (${zone.id})"
    }
}

private const val NO_PERMISSION = "Error: Orbit doesn't have calendar permission. Tell the user to grant it in Orbit's setup."

/** Lists events in a time range. */
class CalendarEventsTool(private val calendar: CalendarAccess, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val spec = ToolSpec(
        name = "calendar_events",
        description = "List the user's calendar events in a time range (recurring events included), with their ids. " +
            "Without a range, the next 7 days.",
        parameters = objectSchema(
            emptyList(),
            "start" to stringProperty("Range start, local date or date-time: '2026-10-08' or '2026-10-08T09:00'. Default: now"),
            "end" to stringProperty("Range end (exclusive), same format. Default: 7 days after start; a date alone means the end of that day"),
            "query" to stringProperty("Only events whose title, location or calendar contains this"),
            "limit" to integerProperty("Most events to return (default 25)", minimum = 1, maximum = 100),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        if (!calendar.canRead) return ToolOutcome(NO_PERMISSION)
        val zone = zone()
        val from = args.string("start")?.let { CalendarFormat.parse(it) ?: return ToolOutcome("Error: start '$it' isn't a date or date-time") }
        val to = args.string("end")?.let { CalendarFormat.parse(it) ?: return ToolOutcome("Error: end '$it' isn't a date or date-time") }
        val begin = from?.first?.atZone(zone)?.toInstant() ?: Instant.now()
        // A bare end date includes that whole day.
        val end = to?.let { (t, dateOnly) -> (if (dateOnly) t.plusDays(1) else t).atZone(zone).toInstant() }
            ?: begin.atZone(zone).plusDays(7).toInstant()
        if (!end.isAfter(begin)) return ToolOutcome("Error: end must be after start")
        val query = args.string("query")
        val events = calendar.events(begin, end).filter { e ->
            query == null || listOfNotNull(e.title, e.location, e.calendar).any { it.contains(query, ignoreCase = true) }
        }
        val limit = args.int("limit") ?: 25
        EventLog.log("calendar", "Listed ${events.size} events")
        if (events.isEmpty()) return ToolOutcome("No events from ${CalendarFormat.describeRange(begin, end, zone)}.")
        return ToolOutcome(
            buildString {
                append("Events from ${CalendarFormat.describeRange(begin, end, zone)}:")
                events.take(limit).forEach { append("\n").append(CalendarFormat.line(it, zone)) }
                if (events.size > limit) append("\n(${events.size - limit} more; narrow the range.)")
            },
        )
    }
}

/** Adds an event, by default to the calendar chosen in settings ([defaultCalendarId]). */
class CreateCalendarEventTool(
    private val calendar: CalendarAccess,
    private val defaultCalendarId: suspend () -> Long?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Add an event to the user's calendar. Times are the user's local time. For relative dates " +
            "(\"tomorrow\", \"next Friday\") check current_time first.",
        parameters = objectSchema(
            listOf("title", "start"),
            "title" to stringProperty("Event title"),
            "start" to stringProperty("Local date-time '2026-10-08T14:00', or a date '2026-10-08' for an all-day event"),
            "end" to stringProperty("Local end date-time, or last day for an all-day event. Default: one hour after start"),
            "duration_minutes" to integerProperty("Length instead of end", minimum = 1, maximum = 10_080),
            "all_day" to booleanProperty("All-day event"),
            "location" to stringProperty("Where"),
            "description" to stringProperty("Notes, e.g. details copied from the screen"),
            "calendar" to stringProperty("Calendar name, only if the user names one; otherwise their default is used"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        if (!calendar.canWrite) return ToolOutcome(NO_PERMISSION)
        val zone = zone()
        val span = CalendarFormat.span(args.requireString("start"), args.string("end"), args.int("duration_minutes"), args.boolean("all_day"), zone)
            .getOrElse { return ToolOutcome("Error: ${it.message}") }
        val calendars = calendar.calendars()
        val target = CalendarAccess.pick(calendars, args.string("calendar"), defaultCalendarId()) ?: return ToolOutcome(
            "Error: no writable calendar" + (args.string("calendar")?.let { " called '$it'" } ?: "") +
                ". Writable: ${calendars.filter { it.writable }.joinToString { it.name }.ifEmpty { "none" }}",
        )
        val title = args.requireString("title")
        val id = calendar.insert(target, title, span, args.string("location"), args.string("description"))
        EventLog.log("calendar", "Created event $id in ${target.name}")
        return ToolOutcome(
            "Created '$title', ${CalendarFormat.describeTime(span.begin, span.end, span.allDay, zone)}, in ${target.name} [id $id].",
            done = true,
        )
    }
}

/** Deletes a single (non-recurring) event, after the user confirms. */
class DeleteCalendarEventTool(private val calendar: CalendarAccess, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "delete_calendar_event",
        description = "Delete an event by id (from calendar_events or create_calendar_event). Needs the user's " +
            "confirmation: call once without confirmed, ask, then call again with confirmed=true.",
        parameters = objectSchema(
            listOf("id"),
            "id" to integerProperty("Event id"),
            "confirmed" to booleanProperty("True only once the user has confirmed"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        if (!calendar.canWrite) return ToolOutcome(NO_PERMISSION)
        val id = args.int("id")?.toLong() ?: return ToolOutcome("Error: id is required")
        val event = calendar.event(id) ?: return ToolOutcome("No event with id $id.")
        val what = "'${event.title}', ${CalendarFormat.describeTime(event.begin, event.end, event.allDay, zone())}"
        if (event.recurring) return ToolOutcome("$what is a recurring event; Orbit can't delete single occurrences, so it was left alone.")
        if (args.boolean("confirmed") != true) {
            return ToolOutcome("Not deleted yet: ask the user to confirm deleting $what, then call again with confirmed=true.")
        }
        if (!calendar.delete(id)) return ToolOutcome("Error: couldn't delete $what.")
        EventLog.log("calendar", "Deleted event $id")
        return ToolOutcome("Deleted $what.", done = true)
    }
}
