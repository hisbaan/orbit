package com.hisbaan.orbit.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide, timestamped diagnostic log. Everything audio, Bluetooth, speech and agent
 * related goes here so one dump captures the full sequence of a turn. Pure Kotlin so the
 * agent and provider modules can use it; the app plugs in [Sink]s for logcat and a file.
 */
object EventLog {
    private const val MAX_ENTRIES = 1000

    data class Entry(
        val seq: Long,
        val wallTimeMs: Long,
        val tag: String,
        val message: String,
    )

    interface Sink {
        fun write(entry: Entry)
        fun clear() = Unit
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val sinks = CopyOnWriteArrayList<Sink>()
    private var seq = 0L

    fun addSink(sink: Sink) {
        sinks += sink
    }

    fun log(tag: String, message: String) {
        val entry = synchronized(this) {
            Entry(seq = seq++, wallTimeMs = System.currentTimeMillis(), tag = tag, message = message)
                .also { e -> _entries.update { (it + e).takeLast(MAX_ENTRIES) } }
        }
        sinks.forEach { it.write(entry) }
    }

    fun clear() {
        _entries.value = emptyList()
        sinks.forEach { it.clear() }
    }

    fun format(entry: Entry): String = "${timeFormat.get()!!.format(Date(entry.wallTimeMs))} [${entry.tag}] ${entry.message}"

    fun formatWithDate(entry: Entry): String =
        "${dateFormat.get()!!.format(Date(entry.wallTimeMs))} [${entry.tag}] ${entry.message}"

    fun dump(header: String): String = buildString {
        appendLine(header)
        appendLine()
        _entries.value.forEach { appendLine(format(it)) }
    }

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
}
