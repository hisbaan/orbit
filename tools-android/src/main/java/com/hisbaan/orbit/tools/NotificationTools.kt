package com.hisbaan.orbit.tools

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.BundleCompat
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** A notification reduced to what's worth reading out. [lines] are the message(s), oldest first. */
data class NotificationText(val app: String, val title: String?, val lines: List<String>, val postedAt: Long)

/**
 * Reads the notifications currently in the shade, through Orbit's notification listener.
 * Ongoing ones (navigation, downloads), media players (playing or paused) and group summaries
 * are left out; media_info covers what's playing.
 */
class NotificationsTool(private val context: Context) : Tool {
    override val privateResult = true
    override val needsUnlock = true

    override val spec = ToolSpec(
        name = "get_notifications",
        description = "Get the user's current notifications (newest first): app, title, text and age. " +
            "Use it when the user asks about notifications, messages or what they missed. " +
            "Summarize them briefly, grouped by app; read a message out in full only when asked.",
        parameters = objectSchema(
            emptyList(),
            "app" to stringProperty("Only this app's notifications, e.g. 'WhatsApp'"),
            "limit" to integerProperty("Most notifications to return (default 10)", minimum = 1, maximum = 30),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val listener = connectedListener()
            ?: return ToolOutcome("Error: Orbit has no notification access. Tell the user to allow it in Orbit's setup.")
        val active = try {
            listener.activeNotifications.orEmpty().toList()
        } catch (e: SecurityException) {
            return ToolOutcome("Error: Orbit has no notification access. Tell the user to allow it in Orbit's setup.")
        }
        val app = args.string("app")
        val items = readable(active)
            .filter { app == null || it.app.contains(app, ignoreCase = true) }
            .sortedByDescending { it.postedAt }
        return ToolOutcome(format(items, (args.int("limit") ?: 10).coerceIn(1, 30), System.currentTimeMillis(), app))
    }

    /** The listener, asking the system to rebind it if access is granted but it isn't connected. */
    private suspend fun connectedListener(): MediaAccessService? {
        MediaAccessService.connected?.let { return it }
        // Without access there's nothing to wait for.
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return null
        EventLog.log("notifications", "Listener not connected; requesting rebind")
        NotificationListenerService.requestRebind(ComponentName(context, MediaAccessService::class.java))
        repeat(20) {
            delay(100)
            MediaAccessService.connected?.let { return it }
        }
        return null
    }

    private fun readable(active: List<StatusBarNotification>): List<NotificationText> {
        // A summary stands in for its group only when none of the group's children are posted.
        val groupsWithChildren = active
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { it.groupKey }
            .toSet()
        return active
            .filter { it.packageName != context.packageName && !it.isOngoing && !it.isMedia }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 || it.groupKey !in groupsWithChildren }
            .mapNotNull(::toText)
    }

    /** Player controls. Paused players drop the ongoing flag, so [isOngoing] alone lets them through. */
    private val StatusBarNotification.isMedia: Boolean
        get() = notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            notification.category == Notification.CATEGORY_TRANSPORT

    private fun toText(sbn: StatusBarNotification): NotificationText? {
        val extras = sbn.notification.extras
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.takeIf { it.isNotBlank() }
        val lines = messages(extras).ifEmpty {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
                ?: listOfNotNull((extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString())
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (title == null && lines.isEmpty()) return null
        return NotificationText(appLabel(context, sbn.packageName), title, lines, sbn.postTime)
    }

    /** MessagingStyle messages as "Sender: text". The bundle keys are the framework's. */
    private fun messages(extras: Bundle): List<String> {
        val bundles = BundleCompat.getParcelableArray(extras, Notification.EXTRA_MESSAGES, Parcelable::class.java) ?: return emptyList()
        return bundles.filterIsInstance<Bundle>().mapNotNull { message ->
            val text = message.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = BundleCompat.getParcelable(message, "sender_person", Person::class.java)?.name ?: message.getCharSequence("sender")
            // MessagingStyle leaves the sender out of the user's own messages.
            "${sender?.takeIf { it.isNotBlank() } ?: "You"}: $text"
        }
    }

    companion object {
        private const val MAX_LINES = 4
        private const val MAX_LINE_CHARS = 300

        /** The tool result: one block per notification, newest first, with its age. */
        fun format(items: List<NotificationText>, limit: Int, now: Long, app: String? = null): String {
            if (items.isEmpty()) return if (app != null) "No notifications from $app." else "No notifications."
            val shown = items.take(limit)
            return buildString {
                append("${items.size} notification${if (items.size == 1) "" else "s"}, newest first")
                if (items.size > shown.size) append(" (showing ${shown.size})")
                append(":")
                for (n in shown) {
                    append("\n- [${n.app}, ${age(now - n.postedAt)}]")
                    n.title?.let { append(" $it") }
                    val lines = n.lines.takeLast(MAX_LINES)
                    if (n.lines.size > lines.size) append(" (${n.lines.size - lines.size} earlier messages)")
                    for (line in lines) append("\n  ${line.replace('\n', ' ').take(MAX_LINE_CHARS)}")
                }
            }
        }

        private fun age(ms: Long): String {
            val minutes = ms / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 24 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} d ago"
            }
        }
    }
}
