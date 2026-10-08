package com.hisbaan.orbit.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's on-device recognizer. On API 33+ it is fed from Orbit's own capture through a
 * pipe (`EXTRA_AUDIO_SOURCE`), so the Bluetooth route Orbit set up is the one that's heard.
 * Below that, the recognizer opens the mic itself and inherits the route.
 *
 * The recognizer reports start/end of speech, but with a fed stream it never finalizes on its
 * own; Orbit ends the utterance after [END_SILENCE_MS] of silence by closing the stream.
 */
class OnDeviceStt(context: Context) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Text(val text: String) : Result
        data object NoSpeech : Result
        data class Failed(val message: String) : Result
    }

    val isAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /**
     * Listens for one utterance. [audio] carries 16 kHz mono PCM16 chunks; pass null to let the
     * recognizer capture itself. [language] is a BCP-47 tag, null for the device default.
     */
    suspend fun listen(
        audio: ReceiveChannel<ShortArray>?,
        sampleRate: Int,
        language: String?,
        onPartial: (String) -> Unit,
        timeoutMs: Long = 20_000,
    ): Result = withContext(Dispatchers.Main) {
        if (!isAvailable) return@withContext Result.Failed("On-device speech recognition isn't available on this phone.")

        val pipe = if (audio != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ParcelFileDescriptor.createPipe() else null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            if (pipe != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            }
        }

        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        val result = CompletableDeferred<Result>()
        val inputDone = AtomicBoolean(false)
        var lastPartial: String? = null

        try {
            coroutineScope {
                var inSpeech = false
                var speechEndedAt = 0L
                var wordsChangedAt = 0L

                /**
                 * Ends the utterance. With a fed audio source the recognizer never finalizes on
                 * its own (the stream doesn't end), so Orbit closes the stream: EOF makes it
                 * produce results. stopListening() is the backstop, and the only way when the
                 * recognizer owns the mic.
                 */
                fun endInput(reason: String) {
                    if (!inputDone.compareAndSet(false, true)) return
                    EventLog.log("stt", "Ending input: $reason")
                    launch {
                        if (pipe != null) delay(FINALIZE_GRACE_MS)
                        if (!result.isCompleted) recognizer.stopListening()
                    }
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = EventLog.log("stt", "Ready for speech")
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onBeginningOfSpeech() {
                        EventLog.log("stt", "Speech started")
                        inSpeech = true
                    }

                    // Fires on short pauses between words too; only a longer silence ends the turn.
                    override fun onEndOfSpeech() {
                        EventLog.log("stt", "Speech ended")
                        inSpeech = false
                        speechEndedAt = SystemClock.elapsedRealtime()
                    }

                    override fun onPartialResults(partialResults: Bundle) {
                        best(partialResults)?.takeIf { it.isNotBlank() }?.let {
                            if (it != lastPartial) wordsChangedAt = SystemClock.elapsedRealtime()
                            lastPartial = it
                            onPartial(it)
                        }
                    }

                    override fun onResults(results: Bundle) {
                        val text = best(results)?.takeIf { it.isNotBlank() } ?: lastPartial
                        EventLog.log("stt", "Result: ${EventLog.content(text?.let { "\"$it\"" })}")
                        result.complete(if (text.isNullOrBlank()) Result.NoSpeech else Result.Text(text))
                    }

                    override fun onError(error: Int) {
                        EventLog.log("stt", "Error ${errorName(error)}")
                        result.complete(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    lastPartial?.let { Result.Text(it) } ?: Result.NoSpeech
                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) recognizer.triggerModelDownload(intent)
                                    Result.Failed("The speech model for this language isn't downloaded yet. I've asked for it; try again in a minute.")
                                }
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Result.Failed("Orbit needs microphone permission.")
                                else -> Result.Failed("Speech recognition failed (${errorName(error)}).")
                            },
                        )
                    }
                })

                if (pipe != null && audio != null) launch(Dispatchers.IO) { feed(audio, pipe[1], inputDone) }
                // Endpointing. The recognizer's speech detection also fires on noise (wind, music,
                // a cough), so words decide: noise can neither end listening before anything is
                // said nor hold it open once the words have stopped changing.
                val startedAt = SystemClock.elapsedRealtime()
                launch {
                    while (!inputDone.get()) {
                        delay(100)
                        val now = SystemClock.elapsedRealtime()
                        if (lastPartial != null) {
                            val wordsAge = now - wordsChangedAt
                            when {
                                !inSpeech && now - speechEndedAt >= END_SILENCE_MS ->
                                    endInput("${END_SILENCE_MS}ms of silence")
                                wordsAge >= WORDS_SETTLED_MS ->
                                    endInput("no new words for ${wordsAge}ms (sound still detected)")
                            }
                        } else if (now - startedAt >= NO_SPEECH_TIMEOUT_MS + if (inSpeech) NO_SPEECH_GRACE_MS else 0L) {
                            endInput("no words after ${(now - startedAt) / 1000}s")
                        }
                    }
                }
                EventLog.log("stt", "Listening (${if (pipe != null) "fed from Orbit capture" else "recognizer mic"}, ${language ?: "default language"})")
                recognizer.startListening(intent)

                val outcome = withTimeoutOrNull(timeoutMs) { result.await() } ?: run {
                    endInput("hit ${timeoutMs}ms limit")
                    withTimeoutOrNull(FINALIZE_GRACE_MS + 2_000) { result.await() }
                        ?: lastPartial?.let { Result.Text(it) }
                        ?: Result.NoSpeech
                }
                // Closing our read end makes a blocked write in the feeder fail, so it exits.
                pipe?.get(0)?.close()
                // Includes the endpoint, no-speech and finalize-backstop timers, which would
                // otherwise hold this scope open after the result is in.
                coroutineContext.cancelChildren()
                outcome
            }
        } finally {
            recognizer.destroy()
            pipe?.forEach { runCatching { it.close() } }
        }
    }

    /** Writes capture chunks into the pipe until [done]; closing the stream signals end of input. */
    private suspend fun feed(audio: ReceiveChannel<ShortArray>, writeSide: ParcelFileDescriptor, done: AtomicBoolean) {
        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
            var bytes = ByteArray(0)
            try {
                for (chunk in audio) {
                    if (done.get()) break
                    if (bytes.size != chunk.size * 2) bytes = ByteArray(chunk.size * 2)
                    for (i in chunk.indices) {
                        val s = chunk[i].toInt()
                        bytes[2 * i] = (s and 0xFF).toByte()
                        bytes[2 * i + 1] = (s shr 8 and 0xFF).toByte()
                    }
                    out.write(bytes)
                }
            } catch (_: IOException) {
                // Recognizer finished and the read end closed.
            }
        }
        EventLog.log("stt", "Audio input closed")
    }

    private fun best(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private companion object {
        /** Silence after the recognizer's end-of-speech before Orbit ends the utterance. */
        const val END_SILENCE_MS = 1_000L
        /** No new words for this long ends the utterance even while the recognizer still hears sound. */
        const val WORDS_SETTLED_MS = 1_500L
        /** Give up if no words are recognized at all... */
        const val NO_SPEECH_TIMEOUT_MS = 8_000L
        /** ...unless a sound is still going on then, which may yet turn into words. */
        const val NO_SPEECH_GRACE_MS = 3_000L
        /** Time for the recognizer to finalize after EOF before forcing stopListening(). */
        const val FINALIZE_GRACE_MS = 1_500L
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "error$error"
    }
}
