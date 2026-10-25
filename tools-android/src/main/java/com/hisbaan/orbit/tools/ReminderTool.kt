package com.hisbaan.orbit.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reminders in Google Tasks, where Gemini's go. Android's CREATE_REMINDER intent opens Tasks'
 * new-task sheet filled in (title, details, exact time), and [TasksSheet] taps Save. Without
 * Orbit's accessibility service the sheet stays open for the user to save. Tasks has no way for
 * other apps to add a task silently (its AddTask broadcast is Gmail's only), and its web API
 * keeps the date but drops the time.
 */
class CreateReminderTool(
    private val context: Context,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Add a reminder to the user's to-do list (Google Tasks), where their reminders live; it " +
            "notifies them at the time. For \"remind me to...\". Times are the user's local time; for relative " +
            "times (\"in two hours\", \"tomorrow at 5\") check current_time first.",
        parameters = objectSchema(
            listOf("title"),
            "title" to stringProperty("What to remind them of, short, e.g. 'Call mom'"),
            "time" to stringProperty("Local date-time '2026-10-08T17:00'. Leave out for a reminder with no time"),
            "notes" to stringProperty("Details, e.g. copied from the screen"),
        ),
    )

    @SuppressLint("InlinedApi") // Strings; on older Android nothing handles the action, which resolve() catches.
    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val title = args.requireString("title").trim()
        val zone = zone()
        val at = args.string("time")?.let { text ->
            val (time, dateOnly) = CalendarFormat.parse(text, zone) ?: return ToolOutcome("Error: time '$text' isn't a date-time like 2026-10-08T17:00")
            if (dateOnly) return ToolOutcome("Error: time needs a time of day ('${text}T09:00'). If the user didn't say one, ask.")
            time.atZone(zone).toInstant().also {
                if (it.isBefore(Instant.now())) return ToolOutcome("Error: $text has already passed. Check current_time.")
            }
        }
        val intent = Intent(Intent.ACTION_CREATE_REMINDER)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.string("notes")?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        at?.let { intent.putExtra(Intent.EXTRA_TIME, it.toEpochMilli()) }
        val tasks = Intent(intent).setPackage(TasksSheet.PACKAGE)
        val due = at?.let { " for ${DUE.format(it.atZone(zone))}" }.orEmpty()

        if (tasks.resolveActivity(context.packageManager) == null) {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolOutcome("Error: no app on the phone takes reminders. Offer a calendar event or an alarm instead.")
            }
            return ToolOutcome(
                "Queued: the user's reminder app opens with '$title'$due filled in as you reply; they confirm it there.",
                afterTurn = AfterTurnAction("add the reminder '$title'", needsUnlock = true, duringReply = true) { context.startActivity(intent) },
            )
        }
        val service = ScreenControlService.connected
            ?: return ToolOutcome(
                "Queued: Google Tasks opens with '$title'$due filled in as you reply. The user taps Save to keep it. " +
                    "(Orbit can save reminders itself once its accessibility service is on, in Orbit's setup.)",
                afterTurn = AfterTurnAction("add the reminder '$title'", needsUnlock = true, duringReply = true) { context.startActivity(tasks) },
            )
        return ToolOutcome(
            "Queued: '$title'$due is saved in Google Tasks as you reply.",
            done = true,
            afterTurn = AfterTurnAction("add the reminder '$title'", needsUnlock = true, duringReply = true) {
                TasksSheet.add(context, service, tasks, title)
            },
        )
    }

    private companion object {
        val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    }
}

/** Google Tasks' new-task sheet, as CREATE_REMINDER opens it. Verified on Tasks 2026.10.12. */
internal object TasksSheet {
    const val PACKAGE = "com.google.android.apps.tasks"
    private const val SAVE = "add_task_done"
    private const val TITLE = "add_task_title"
    private const val OPEN_TIMEOUT_MS = 5_000L
    private const val CLOSE_TIMEOUT_MS = 3_000L

    /**
     * Opens the sheet with [intent] and saves it, then goes back to the app the user was in (the
     * sheet opens over Tasks' own list). Throws, leaving the sheet open for the user, if the
     * sheet doesn't show [title] or doesn't close: the after-turn error tells them.
     */
    suspend fun add(context: Context, service: ScreenControlService, intent: Intent, title: String) {
        val wasInTasks = service.topApp() == PACKAGE
        context.startActivity(intent)
        val save = service.awaitView(PACKAGE, SAVE, OPEN_TIMEOUT_MS)
            ?: throw IllegalStateException("Tasks didn't open its new-task sheet")
        // Only ever save this reminder, not a draft the sheet kept or a sheet that changed.
        val shown = service.findView(PACKAGE, TITLE)?.text?.toString()?.trim()
        if (shown != title) throw IllegalStateException("Tasks shows '$shown', not '$title'; check it and tap Save")
        if (!service.click(save) || !service.awaitGone(PACKAGE, SAVE, CLOSE_TIMEOUT_MS)) {
            throw IllegalStateException("Tasks didn't save it; tap Save there")
        }
        if (!wasInTasks && service.topApp() == PACKAGE) service.back()
    }
}
