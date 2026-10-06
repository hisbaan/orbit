package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Output usage decides routing: VOICE_COMMUNICATION follows the communication device (SCO). */
enum class PlaybackUsage(val label: String, val value: Int) {
    VOICE_COMMUNICATION("VOICE_COMMUNICATION", AudioAttributes.USAGE_VOICE_COMMUNICATION),
    ASSISTANT("ASSISTANT", AudioAttributes.USAGE_ASSISTANT),
    MEDIA("MEDIA", AudioAttributes.USAGE_MEDIA),
}

fun PlaybackUsage.speechAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(value)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

object PcmPlayer {
    suspend fun play(samples: ShortArray, sampleRate: Int, usage: PlaybackUsage) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(usage.speechAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer, sampleRate / 5 * 2))
            .build()
        // A stream track never plays out a final partial buffer, so the head position would
        // stall short of the end. Trailing silence pushes the real samples through.
        val padded = samples.copyOf(samples.size + sampleRate / 4)
        try {
            track.play()
            var offset = 0
            while (isActive && offset < padded.size) {
                val n = track.write(padded, offset, minOf(2048, padded.size - offset))
                if (n < 0) {
                    EventLog.log("play", "write() error $n")
                    break
                }
                if (offset == 0) EventLog.log("play", "Playing ${usage.label} -> ${track.routedDevice?.describe() ?: "?"}")
                offset += n
            }
            val durationMs = samples.size * 1000L / sampleRate
            val drained = withTimeoutOrNull(durationMs + 1000) {
                while (isActive && track.playbackHeadPosition < samples.size) delay(10)
            }
            if (drained == null) EventLog.log("play", "Drain timed out at ${track.playbackHeadPosition}/${samples.size} frames")
        } finally {
            track.stop()
            track.release()
        }
    }

    fun beep(sampleRate: Int, frequencyHz: Double = 880.0, durationMs: Int = 180): ShortArray {
        val n = sampleRate * durationMs / 1000
        val fade = sampleRate / 100
        return ShortArray(n) { i ->
            val envelope = minOf(1.0, i.toDouble() / fade, (n - i).toDouble() / fade)
            (sin(2 * PI * frequencyHz * i / sampleRate) * envelope * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
