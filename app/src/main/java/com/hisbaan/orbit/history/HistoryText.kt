package com.hisbaan.orbit.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One event as shown: a [label] ("Tool · play_music"), a short [summary], and [details] for when it's opened. */
data class HistoryRow(val at: Long, val label: String, val summary: String, val details: String?, val problem: Boolean)

/** How history events read, on screen and when copied or shared. */
object HistoryText {
    fun rows(turn: TurnRecord): List<HistoryRow> = turn.entries.map { (at, event) ->
        val (label, summary, details) = describe(event)
        HistoryRow(at, label, summary, details, event.problem)
    }

    /** The whole conversation as plain text, details included. */
    fun export(conversation: StoredConversation): String = buildString {
        appendLine("Orbit conversation, ${dateTime(conversation.startedAt)}")
        conversation.turns.forEachIndexed { i, turn ->
            appendLine()
            appendLine("Turn ${i + 1} · ${time(turn.startedAt)} · ${turn.source}")
            for (row in rows(turn)) {
                appendLine("${time(row.at)}  ${row.label}${if (row.problem) " (!)" else ""}: ${row.summary}")
                row.details?.lines()?.forEach { appendLine("    $it") }
            }
        }
    }

    fun dateTime(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun describe(event: HistoryEvent): Triple<String, String, String?> = when (event) {
        is HistoryEvent.Heard -> Triple(if (event.typed) "You typed" else "Heard", quote(event.text), null)
        is HistoryEvent.HeardNothing -> Triple("Heard nothing", event.what, null)
        is HistoryEvent.ListeningAgain -> Triple("Listening again", "Because ${event.why}", null)
        HistoryEvent.MemoryExpired -> Triple("Model's memory expired", "It went on without the earlier messages", null)
        is HistoryEvent.ModelStep -> Triple(
            "Model · step ${event.step}",
            listOfNotNull(
                duration(event.durationMs),
                event.finishReason?.let { "finish: $it" },
                when {
                    event.toolCalls.isNotEmpty() -> "called ${event.toolCalls.joinToString { it.name }}"
                    event.text.isNotBlank() -> "answered"
                    else -> null
                },
            ).joinToString(" · "),
            buildString {
                if (event.text.isNotBlank()) section("Text", event.text)
                event.toolCalls.forEach { section("Call ${it.name} (${it.id})", pretty(it.arguments)) }
                section("Model", event.model)
                section("Sent", "The system prompt, ${event.messages} messages and ${event.tools.size} tools")
                if (event.tools.isNotEmpty()) section("Tools offered", event.tools.joinToString())
                section("System prompt", event.systemPrompt ?: "Same as the previous step's")
            },
        )
        is HistoryEvent.ModelFailed -> Triple(
            "Model · step ${event.step} failed",
            "${duration(event.durationMs)} · ${event.error}",
            "Model: ${event.model}",
        )
        is HistoryEvent.ToolRan -> Triple(
            "Tool · ${event.call.name}",
            listOf(
                duration(event.durationMs),
                when {
                    event.refused != null -> "not run: ${event.refused}"
                    event.error != null -> "failed"
                    event.held != null -> "waiting for the user's yes"
                    event.done -> "done"
                    else -> "returned a result"
                },
            ).joinToString(" · "),
            buildString {
                section("Arguments", pretty(event.call.arguments))
                section("Result sent to the model", event.result)
                event.error?.let { section("Error", it) }
                event.held?.let { section("Held as", it) }
                event.afterTurn?.let { section("After the reply", it) }
                if (event.cards.isNotEmpty()) section("Cards", event.cards.joinToString())
                if (event.images.isNotEmpty()) section("Images", event.images.joinToString("\n"))
            },
        )
        is HistoryEvent.TextWithdrawn -> Triple("Withdrawn", quote(event.text), "Written alongside tool calls, then taken back because the turn went on.")
        is HistoryEvent.Confirmed -> Triple("Confirmed", "${event.ref}: ${event.description}", null)
        HistoryEvent.GaveUp -> Triple("Gave up", "The model used every step without finishing", null)
        is HistoryEvent.Reply -> Triple("Reply", quote(event.text) + if (event.interrupted) " (interrupted)" else "", null)
        is HistoryEvent.Said -> Triple("Said", quote(event.text), event.error?.let { "Error shown: $it" })
        is HistoryEvent.AgentFailed -> Triple("Couldn't reach the model", event.error, null)
        is HistoryEvent.UnlockAsked -> Triple("Asked to unlock", "For ${event.forWhat}: ${if (event.unlocked) "unlocked" else "not unlocked"}", null)
        is HistoryEvent.AfterTurn -> Triple(
            "After the reply",
            "${event.description}: " + when (event.outcome) {
                HistoryEvent.AfterTurn.Outcome.RAN -> "done"
                HistoryEvent.AfterTurn.Outcome.SKIPPED_LOCKED -> "skipped, phone locked"
                HistoryEvent.AfterTurn.Outcome.FAILED -> "failed"
            },
            event.error,
        )
        HistoryEvent.Cancelled -> Triple("Cancelled", "Stopped by a press or a new question", null)
        is HistoryEvent.Failed -> Triple("Turn failed", event.error, null)
    }

    private fun StringBuilder.section(title: String, body: String) {
        if (isNotEmpty()) append("\n\n")
        append(title).append(":\n").append(body)
    }

    private fun quote(text: String) = "“$text”"

    private fun duration(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(Locale.US, ms / 1000.0)

    private val prettyJson = Json { prettyPrint = true }

    /** Tool arguments indented for reading; as they came if they aren't JSON. */
    private fun pretty(json: String): String = try {
        prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json))
    } catch (e: Exception) {
        json.ifBlank { "(none)" }
    }
}
