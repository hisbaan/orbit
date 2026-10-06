package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class CaptureSource(val label: String, val value: Int) {
    VOICE_RECOGNITION("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    MIC("MIC", MediaRecorder.AudioSource.MIC),
    DEFAULT("DEFAULT", MediaRecorder.AudioSource.DEFAULT),
    UNPROCESSED("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
}

/**
 * Fixed-capacity sample store fed from a running capture, so callers can slice out audio
 * while recording continues (keeping the Bluetooth link up). Samples past capacity are dropped.
 */
class CaptureBuffer(maxMs: Long) {
    private val data = ShortArray((MicCapture.SAMPLE_RATE * maxMs / 1000).toInt())

    @Volatile
    var size = 0
        private set

    fun append(samples: ShortArray, count: Int) {
        val n = minOf(count, data.size - size)
        if (n <= 0) return
        samples.copyInto(data, size, 0, n)
        size += n
    }

    fun copy(from: Int = 0, to: Int = size): ShortArray = data.copyOfRange(from, to)
}

/** 16 kHz mono PCM16 capture: the format on-device STT wants, and what wideband SCO carries. */
object MicCapture {
    const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = SAMPLE_RATE / 50 // 20 ms
    private const val SILENT_DBFS = -60f

    class Result(
        val samplesRecorded: Long,
        val routedDevice: AudioDeviceInfo?,
        val maxRmsDbfs: Float,
    ) {
        val durationMs: Long get() = samplesRecorded * 1000L / SAMPLE_RATE
    }

    /** One 20 ms chunk. [samples] is reused between calls: copy what you keep. */
    fun interface ChunkListener {
        fun onChunk(samples: ShortArray, count: Int, rmsDbfs: Float, peakDbfs: Float)
    }

    /**
     * Records until [stop] is set or the coroutine is cancelled, handing every 20 ms chunk
     * to [onChunk] on the capture thread.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        source: CaptureSource,
        preferredDevice: AudioDeviceInfo?,
        stop: AtomicBoolean,
        onChunk: ChunkListener,
    ): Result = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(source.value)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE / 5 * 2))
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            EventLog.log("mic", "AudioRecord failed to initialize (source=${source.label})")
            record.release()
            return@withContext Result(0, null, Float.NEGATIVE_INFINITY)
        }

        preferredDevice?.let {
            EventLog.log("mic", "setPreferredDevice(${it.describe()}) = ${record.setPreferredDevice(it)}")
        }
        val routingListener = AudioRouting.OnRoutingChangedListener { r ->
            EventLog.log("mic", "Capture routed -> ${r.routedDevice?.describe() ?: "none"}")
        }
        record.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))

        var maxRms = Float.NEGATIVE_INFINITY
        var routed: AudioDeviceInfo? = null
        var total = 0L
        val chunk = ShortArray(CHUNK_SAMPLES)

        try {
            record.startRecording()
            EventLog.log(
                "mic",
                "Recording source=${source.label} state=${record.recordingState} " +
                    "session=${record.audioSessionId}",
            )
            while (isActive && !stop.get()) {
                val n = record.read(chunk, 0, CHUNK_SAMPLES)
                if (n < 0) {
                    EventLog.log("mic", "read() error $n")
                    break
                }
                if (total == 0L && n > 0) {
                    routed = record.routedDevice
                    val silenced = record.activeRecordingConfiguration?.isClientSilenced
                    EventLog.log("mic", "First audio: routed=${routed?.describe() ?: "?"} silenced=$silenced")
                }
                total += n
                val (rms, peak) = levels(chunk, 0, n)
                maxRms = max(maxRms, rms)
                onChunk.onChunk(chunk, n, rms, peak)
            }
        } finally {
            record.stop()
            record.removeOnRoutingChangedListener(routingListener)
            record.release()
        }

        val result = Result(total, routed, maxRms)
        EventLog.log(
            "mic",
            "Recorded ${result.durationMs}ms, max RMS ${"%.1f".format(maxRms)} dBFS" +
                if (maxRms < SILENT_DBFS) " (looks SILENT)" else "",
        )
        result
    }

    private fun levels(samples: ShortArray, offset: Int, length: Int): Pair<Float, Float> {
        if (length <= 0) return Float.NEGATIVE_INFINITY to Float.NEGATIVE_INFINITY
        var sumSquares = 0.0
        var peak = 0
        for (i in offset until offset + length) {
            val s = samples[i].toInt()
            sumSquares += s.toDouble() * s
            peak = max(peak, abs(s))
        }
        val rms = sqrt(sumSquares / length) / Short.MAX_VALUE
        return toDbfs(rms) to toDbfs(peak.toDouble() / Short.MAX_VALUE)
    }

    private fun toDbfs(linear: Double): Float = (20 * log10(max(linear, 1e-5))).toFloat()
}
