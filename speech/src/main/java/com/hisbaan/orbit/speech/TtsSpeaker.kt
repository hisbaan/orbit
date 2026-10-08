package com.hisbaan.orbit.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.audio.speechAttributes
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        EventLog.log("tts", "TextToSpeech init ${if (ok) "ok" else "failed ($status)"}")
        ready.complete(ok)
    }

    /**
     * Utterances still being spoken, by id, across every caller. The engine has one progress
     * listener, so it's set once here: per-call listeners replaced each other when calls
     * overlapped (a turn's reply speaker waits from the start of the turn; an unlock prompt
     * speaks meanwhile), and the first caller's utterances never heard they were done.
     */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(true)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                pending.remove(utteranceId)?.complete(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.complete(false)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                EventLog.log("tts", "Utterance error $errorCode")
                pending.remove(utteranceId)?.complete(false)
            }
        })
    }

    /** The voice to speak with, by [Voice.getName]; null or not installed uses the engine's default. */
    @Volatile
    var voiceName: String? = null

    /** The engine's voices (installed or not), once it's ready. */
    suspend fun voices(): List<Voice> = if (ready.await()) tts.voices.orEmpty().toList() else emptyList()

    suspend fun speak(text: String, usage: PlaybackUsage): Boolean =
        speakAll(Channel<String>(1).apply { trySend(text); close() }, usage)

    /**
     * Speaks [sentences] back to back as they arrive, so speech starts before the whole reply
     * exists. Returns once the channel is closed and everything queued has been spoken; false
     * if any utterance failed. Cancelling stops speech immediately.
     */
    suspend fun speakAll(sentences: ReceiveChannel<String>, usage: PlaybackUsage): Boolean {
        if (!ready.await()) return false
        tts.setAudioAttributes(usage.speechAttributes())
        applyVoice()
        val queued = mutableListOf<CompletableDeferred<Boolean>>()
        val ids = mutableListOf<String>()
        try {
            for (sentence in sentences) {
                val id = UUID.randomUUID().toString()
                val done = CompletableDeferred<Boolean>()
                pending[id] = done
                ids += id
                // The first utterance flushes anything left over from an earlier turn.
                val mode = if (queued.isEmpty()) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (tts.speak(sentence, mode, null, id) != TextToSpeech.SUCCESS) {
                    EventLog.log("tts", "speak() rejected")
                    pending.remove(id)
                    continue
                }
                if (queued.isEmpty()) EventLog.log("tts", "Speaking (${usage.label})")
                queued += done
            }
            var ok = queued.isNotEmpty()
            for (done in queued) ok = (withTimeoutOrNull(30_000) { done.await() } ?: false) && ok
            return ok
        } catch (e: CancellationException) {
            if (queued.isNotEmpty()) tts.stop()
            throw e
        } finally {
            ids.forEach(pending::remove) // any that timed out or were cut short
        }
    }

    /**
     * Switches to [voiceName]. An online voice without a validated network falls back to its
     * offline twin (`-network` → `-local`), else the default, rather than stalling on a timeout.
     */
    private fun applyVoice() {
        val voices = tts.voices.orEmpty()
        var voice = voiceName?.let { name -> voices.firstOrNull { it.name == name && it.isInstalled } }
        if (voice != null && voice.isNetworkConnectionRequired && !online()) {
            val name = voice.name
            voice = voices.firstOrNull { it.name == name.removeSuffix("-network") + "-local" && it.isInstalled }
            EventLog.log("tts", "Offline: $name -> ${voice?.name ?: "default voice"}")
        }
        val target = voice ?: tts.defaultVoice ?: return
        if (tts.voice?.name != target.name) {
            tts.voice = target
            EventLog.log("tts", "Voice: ${target.name}")
        }
    }

    private fun online(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun shutdown() = tts.shutdown()
}

/** False for voices the engine lists but hasn't downloaded. */
val Voice.isInstalled: Boolean get() = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features
