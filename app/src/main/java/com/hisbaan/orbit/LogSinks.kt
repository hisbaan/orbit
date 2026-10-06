package com.hisbaan.orbit

import android.util.Log
import com.hisbaan.orbit.diagnostics.EventLog
import java.io.File
import java.util.concurrent.Executors

/** Mirrors the event log to logcat under `Orbit/<tag>`. */
object LogcatSink : EventLog.Sink {
    override fun write(entry: EventLog.Entry) {
        Log.i("Orbit/${entry.tag}", entry.message)
    }
}

/**
 * Appends the event log to a file so runs can be pulled after the fact
 * (debug builds: `adb shell run-as com.hisbaan.orbit cat files/eventlog.txt`).
 */
class FileSink(dir: File) : EventLog.Sink {
    private val file = File(dir, FILE_NAME)
    private val writer = Executors.newSingleThreadExecutor()

    override fun write(entry: EventLog.Entry) {
        val line = EventLog.formatWithDate(entry)
        writer.execute {
            try {
                if (file.length() > MAX_FILE_BYTES) file.renameTo(File(file.parentFile, "$FILE_NAME.old"))
                file.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w("Orbit/log", "Failed to write $FILE_NAME", e)
            }
        }
    }

    override fun clear() {
        writer.execute { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "eventlog.txt"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
