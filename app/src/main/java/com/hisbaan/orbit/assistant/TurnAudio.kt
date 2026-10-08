package com.hisbaan.orbit.assistant

import android.bluetooth.BluetoothDevice
import com.hisbaan.orbit.audio.AudioFocus
import com.hisbaan.orbit.audio.AudioRouter
import com.hisbaan.orbit.audio.CaptureSource
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.audio.Earcons
import com.hisbaan.orbit.audio.FocusMode
import com.hisbaan.orbit.audio.MicCapture
import com.hisbaan.orbit.audio.PcmPlayer
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.audio.RouteStrategy
import com.hisbaan.orbit.speech.OnDeviceStt
import com.hisbaan.orbit.speech.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One voice turn's audio, in the order validated in the Mic Lab (PLAN.md §1): focus → route
 * → one capture stream for the whole turn → listen and speak → stop capture → release the
 * route → wait for the HFP link to drop → abandon focus. The single capture stream keeps the
 * SCO link up between listening and speaking; its chunks go to the recognizer only while it
 * listens.
 *
 * [open] sets things up one by one and [close] undoes whatever it got to, so a turn cancelled
 * while starting (a second press, the user typing) leaves no focus or route behind.
 * [scope] runs the capture and the link watcher; [log] is the turn's step log.
 */
class TurnAudio(
    private val focus: AudioFocus,
    private val router: AudioRouter,
    private val stt: OnDeviceStt,
    private val tts: TtsSpeaker,
    private val scope: CoroutineScope,
    private val phase: () -> Phase,
    private val log: (String) -> Unit,
) : Voice {
    private val stopCapture = AtomicBoolean(false)
    private val captureEnded = AtomicBoolean(false)
    private val listener = AtomicReference<Channel<ShortArray>?>(null)
    private var route: AudioRouter.Route? = null
    private var capture: Job? = null
    private var linkWatch: Job? = null
    private var usage = PlaybackUsage.ASSISTANT
    private var sounds = EarconStyle.GENTLE

    /**
     * Takes focus and the route to [device] (else the first HFP headset), and starts capture if
     * [captureMic]. [soundsFor] picks the listening sounds for the headset routed to (null: the
     * phone).
     */
    suspend fun open(device: BluetoothDevice?, captureMic: Boolean, voiceName: String?, soundsFor: (BluetoothDevice?) -> EarconStyle) {
        tts.voiceName = voiceName
        focus.acquire(FocusMode.TRANSIENT_EXCLUSIVE)
        val acquired = router.acquire(RouteStrategy.AUTO, setCommunicationMode = false, preferredDevice = device)
        route = acquired
        usage = if (acquired.isBluetooth) PlaybackUsage.VOICE_COMMUNICATION else PlaybackUsage.ASSISTANT
        log("Route: ${acquired.summary()}")
        sounds = soundsFor(acquired.hfpDevice ?: device)
        log("Listening sounds: ${sounds.label}")

        if (captureMic) {
            capture = scope.launch {
                MicCapture.record(CaptureSource.VOICE_RECOGNITION, null, stopCapture) { samples, count, _, _ ->
                    listener.get()?.trySend(samples.copyOf(count))
                }
                if (!stopCapture.get()) {
                    // The mic couldn't be opened, or stopped mid-turn. End a listen in progress
                    // now rather than after it gives up on silence.
                    captureEnded.set(true)
                    log("Capture ended early")
                    listener.get()?.close()
                }
            }
        }
        linkWatch = scope.launch(Dispatchers.IO) { watchHfpLink(acquired) }
        router.awaitHfpAudio(acquired, timeoutMs = 1_500)?.let { log("HFP link up after ${it}ms") }
    }

    /** Releases whatever [open] set up, waiting for the HFP link to drop before giving up focus. */
    suspend fun close() = withContext(NonCancellable) {
        linkWatch?.cancel()
        listener.getAndSet(null)?.close()
        stopCapture.set(true)
        capture?.join()
        route?.let { r ->
            router.release(r)
            router.awaitHfpAudioDown(r, timeoutMs = 3_000)?.let { log("HFP link down after ${it}ms") }
        }
        focus.release()
    }

    override suspend fun listen(onPartial: (String) -> Unit): Heard {
        if (captureEnded.get()) return Heard.MicUnavailable
        PcmPlayer.play(Earcons.listening(sounds, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, usage)
        val audio = Channel<ShortArray>(capacity = 500)
        listener.set(audio)
        val result = try {
            stt.listen(audio, MicCapture.SAMPLE_RATE, language = null, onPartial = onPartial)
        } finally {
            listener.set(null)
            audio.close()
        }
        PcmPlayer.play(Earcons.done(sounds, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, usage)
        return when (result) {
            is OnDeviceStt.Result.Text -> Heard.Text(result.text)
            OnDeviceStt.Result.NoSpeech -> if (captureEnded.get()) Heard.MicUnavailable else Heard.Silence
            is OnDeviceStt.Result.Failed -> Heard.Failed(result.message)
        }
    }

    override suspend fun say(text: String) {
        tts.speak(text, usage)
    }

    override suspend fun sayAll(sentences: ReceiveChannel<String>) {
        tts.speakAll(sentences, usage)
    }

    /** Logs when the headset drops the HFP audio link mid-turn, e.g. its button ending voice recognition. Polls; run it off Main. */
    private suspend fun watchHfpLink(route: AudioRouter.Route) {
        if (!route.isBluetooth) return
        var wasUp = false
        while (true) {
            val up = router.isHfpAudioUp(route) ?: return
            if (wasUp && !up) log("HFP link dropped mid-turn (phase ${phase()})")
            wasUp = up
            delay(100)
        }
    }
}
