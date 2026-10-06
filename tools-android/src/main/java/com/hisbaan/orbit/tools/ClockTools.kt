package com.hisbaan.orbit.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Timers via the clock app, without showing its UI. Immediate. */
class SetTimerTool(private val context: Context) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer.",
        parameters = objectSchema(
            listOf("seconds"),
            "seconds" to integerProperty("Timer length in seconds", minimum = 1, maximum = 86_400),
            "label" to stringProperty("Optional label, e.g. 'pasta'"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val seconds = args.int("seconds")?.takeIf { it in 1..86_400 }
            ?: return ToolOutcome("Error: seconds must be between 1 and 86400")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
        return ToolOutcome("Timer set for $seconds seconds.", done = true)
    }
}

/** Alarms via the clock app, without showing its UI. Immediate. */
class SetAlarmTool(private val context: Context) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm for a time of day (the next occurrence of that time).",
        parameters = objectSchema(
            listOf("hour", "minute"),
            "hour" to integerProperty("Hour, 24-hour clock", minimum = 0, maximum = 23),
            "minute" to integerProperty("Minute", minimum = 0, maximum = 59),
            "label" to stringProperty("Optional label"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val hour = args.int("hour")?.takeIf { it in 0..23 } ?: return ToolOutcome("Error: hour must be 0-23")
        val minute = args.int("minute")?.takeIf { it in 0..59 } ?: return ToolOutcome("Error: minute must be 0-59")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
        return ToolOutcome("Alarm set for %02d:%02d.".format(hour, minute), done = true)
    }
}

/**
 * The current date and time. A tool rather than part of the system prompt, so the prompt
 * stays identical between requests and the provider's prompt cache keeps hitting.
 */
class CurrentTimeTool(private val clock: () -> ZonedDateTime = ZonedDateTime::now) : Tool {
    override val spec = ToolSpec(
        name = "current_time",
        description = "Get the current date, time and time zone. Use it for the time or date, and before working out " +
            "anything relative to now (\"in two hours\", \"next Friday\").",
        parameters = objectSchema(),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val now = clock()
        return ToolOutcome("${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${now.zone.id})")
    }
}
