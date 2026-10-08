package com.hisbaan.orbit.assistant

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Agent
import com.hisbaan.orbit.agent.Card
import com.hisbaan.orbit.agent.SentenceChunker
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.audio.AudioFocus
import com.hisbaan.orbit.audio.AudioRouter
import com.hisbaan.orbit.audio.CaptureSource
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.audio.Earcons
import com.hisbaan.orbit.audio.FocusMode
import com.hisbaan.orbit.audio.HeadsetProfile
import com.hisbaan.orbit.audio.MicCapture
import com.hisbaan.orbit.audio.PcmPlayer
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.audio.RouteStrategy
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ApiKeyCredential
import com.hisbaan.orbit.providers.OpenAiChatCompletions
import com.hisbaan.orbit.providers.ProviderException
import com.hisbaan.orbit.settings.AppSettings
import com.hisbaan.orbit.settings.SettingsRepository
import com.hisbaan.orbit.speech.OnDeviceStt
import com.hisbaan.orbit.speech.TtsSpeaker
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Phase { IDLE, STARTING, LISTENING, THINKING, SPEAKING, FINISHING }

data class AssistantState(
    val phase: Phase = Phase.IDLE,
    val partialTranscript: String = "",
    val transcript: String? = null,
    val reply: String? = null,
    val actions: List<String> = emptyList(),
    val error: String? = null,
    /** Shown under the reply, e.g. a weather card. */
    val cards: List<Card> = emptyList(),
)

/**
 * Runs voice turns. The audio sequence is the one validated in the Mic Lab (PLAN.md §1):
 * focus → route → one capture stream for the whole turn → earcon → listen → think → speak →
 * stop capture → release route → wait for the HFP link to drop → abandon focus → after-turn
 * actions. A turn can hold several listen/speak exchanges (follow-up questions, interruptions)
 * on the same route.
 */
class Assistant(
    context: Context,
    private val settings: SettingsRepository,
    private val httpClient: HttpClient,
    tools: List<Tool>,
    private val tts: TtsSpeaker,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val headsetProfile = HeadsetProfile(appContext)
    private val router = AudioRouter(audioManager, headsetProfile)
    private val focus = AudioFocus(audioManager)
    private val stt = OnDeviceStt(appContext)
    private val agent = Agent(tools, systemPrompt = { SystemPrompt.build() })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var turn: Job? = null

    /** True while a streamed reply is being spoken: a trigger then interrupts instead of cancelling. */
    @Volatile
    private var interruptible = false
    private val interrupts = Channel<Unit>(Channel.CONFLATED)
    private var lastTriggerSource: String? = null
    private var lastTriggerAt = 0L

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Emitted before after-turn actions run, so the overlay gets out of the way of the app they open. */
    private val _dismissRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissRequests: SharedFlow<Unit> = _dismissRequests.asSharedFlow()

    /**
     * Asks the user to unlock before an after-turn action opens another app's UI. Returns
     * true if the device is unlocked afterwards.
     */
    @Volatile
    var keyguardDismisser: (suspend () -> Boolean)? = null

    /** Waits until settings tools read synchronously have loaded (see OrbitApp). */
    @Volatile
    var settingsReady: (suspend () -> Unit)? = null

    init {
        // Bind the HFP proxy now so a headset trigger doesn't wait for it.
        scope.launch { headsetProfile.get() }
    }

    /**
     * Starts a turn. During a turn, a trigger interrupts Orbit's speech and listens again; at
     * any other point it cancels the turn (press again to stop). [text] skips the first
     * listen, for testing.
     */
    fun trigger(source: String, device: BluetoothDevice?, text: String? = null) {
        val now = SystemClock.elapsedRealtime()
        if (source == lastTriggerSource && now - lastTriggerAt < DUPLICATE_TRIGGER_MS) {
            // One power-button press sometimes reaches MainActivity twice, ~10 ms apart.
            EventLog.log("turn", "Ignored duplicate trigger from $source (+${now - lastTriggerAt}ms)")
            return
        }
        lastTriggerSource = source
        lastTriggerAt = now
        if (turn?.isActive == true) {
            if (interruptible) {
                EventLog.log("turn", "Trigger from $source while speaking: interrupting to listen")
                interrupts.trySend(Unit)
            } else {
                EventLog.log("turn", "Trigger from $source during a turn: cancelling")
                turn?.cancel()
            }
            return
        }
        turn = scope.launch { runTurn(source, device, text) }
    }

    fun cancel() {
        turn?.cancel()
    }

    /**
     * Answers typed [text] on screen only: no microphone, no speech, no follow-up listening
     * (typing means talking out loud isn't an option). Replaces any turn in progress.
     */
    fun ask(text: String) {
        val previous = turn
        turn = scope.launch {
            previous?.cancelAndJoin()
            runTextTurn(text)
        }
    }

    /** Stops a voice turn that hasn't heard anything yet, e.g. because the user started typing instead. */
    fun stopListening() {
        if (_state.value.phase in setOf(Phase.STARTING, Phase.LISTENING)) turn?.cancel()
    }

    private suspend fun runTurn(source: String, device: BluetoothDevice?, typed: String?) {
        val t0 = SystemClock.elapsedRealtime()
        val step: (String) -> Unit = { EventLog.log("turn", "+${SystemClock.elapsedRealtime() - t0}ms $it") }
        step("Start: trigger=$source device=${device?.address ?: "?"}")
        _state.value = AssistantState(phase = Phase.STARTING)

        val config = settings.current()
        tts.voiceName = config.ttsVoice
        focus.acquire(FocusMode.TRANSIENT_EXCLUSIVE)
        val route = router.acquire(RouteStrategy.AUTO, setCommunicationMode = false, preferredDevice = device)
        val usage = if (route.isBluetooth) PlaybackUsage.VOICE_COMMUNICATION else PlaybackUsage.ASSISTANT
        step("Route: ${route.summary()}")
        val sounds = earconStyle(config, route.hfpDevice ?: device)
        step("Listening sounds: ${sounds.label}")

        // One capture stream for the whole turn keeps the SCO link up. Chunks go to the
        // recognizer only while it's listening.
        val stopCapture = AtomicBoolean(false)
        val listener = AtomicReference<Channel<ShortArray>?>(null)
        val capture = scope.launch {
            MicCapture.record(CaptureSource.VOICE_RECOGNITION, null, stopCapture) { samples, count, _, _ ->
                listener.get()?.trySend(samples.copyOf(count))
            }
        }
        val linkWatch = scope.launch(Dispatchers.IO) { watchHfpLink(route, step) }

        val afterTurn = mutableListOf<AfterTurnAction>()
        try {
            router.awaitHfpAudio(route, timeoutMs = 1_500)?.let { step("HFP link up after ${it}ms") }
            converse(config, usage, sounds, listener, typed, afterTurn, step)
        } finally {
            withContext(NonCancellable) {
                interruptible = false
                _state.update { it.copy(phase = Phase.FINISHING) }
                linkWatch.cancel()
                listener.getAndSet(null)?.close()
                stopCapture.set(true)
                capture.join()
                router.release(route)
                router.awaitHfpAudioDown(route, timeoutMs = 3_000)?.let { step("HFP link down after ${it}ms") }
                focus.release()
                _state.update { it.copy(phase = Phase.IDLE) }
                step("Turn finished")
            }
        }
        if (afterTurn.isNotEmpty()) _dismissRequests.tryEmit(Unit)
        runAfterTurn(afterTurn)
    }

    private suspend fun runTextTurn(text: String) {
        val t0 = SystemClock.elapsedRealtime()
        val step: (String) -> Unit = { EventLog.log("turn", "+${SystemClock.elapsedRealtime() - t0}ms $it") }
        step("Start: typed")
        _state.value = AssistantState(phase = Phase.THINKING, transcript = text)
        val config = settings.current()
        val afterTurn = mutableListOf<AfterTurnAction>()
        try {
            if (!config.isProviderConfigured) {
                return speak("Orbit isn't set up yet. Add a provider and model in Orbit's settings.", usage = null)
            }
            thinkAndSpeak(text, transport(config), config.model, usage = null, afterTurn, step)
        } finally {
            _state.update { it.copy(phase = Phase.IDLE) }
            step("Turn finished")
        }
        if (afterTurn.isNotEmpty()) _dismissRequests.tryEmit(Unit)
        runAfterTurn(afterTurn)
    }

    private fun transport(config: AppSettings) =
        OpenAiChatCompletions(config.baseUrl, ApiKeyCredential(config.apiKey), httpClient, config.reasoningEffort)

    /**
     * The exchanges of one turn: listen → think and speak. Orbit listens again, without a
     * button press, when its reply asks a question (an answer, a confirmation) or when the
     * user interrupts it.
     */
    private suspend fun converse(
        config: AppSettings,
        usage: PlaybackUsage,
        sounds: EarconStyle,
        listener: AtomicReference<Channel<ShortArray>?>,
        typed: String?,
        afterTurn: MutableList<AfterTurnAction>,
        step: (String) -> Unit,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return speak("Orbit needs microphone permission. Open Orbit to grant it.", usage, error = "Microphone permission missing")
        }
        if (!config.isProviderConfigured) {
            return speak("Orbit isn't set up yet. Add a provider and model in Orbit's settings.", usage)
        }
        val transport = transport(config)

        var followUp = false
        repeat(MAX_EXCHANGES) { exchange ->
            val text = if (exchange == 0 && typed != null) {
                typed
            } else {
                PcmPlayer.play(Earcons.listening(sounds, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, usage)
                _state.value = AssistantState(phase = Phase.LISTENING)
                step(if (followUp) "Listening for a follow-up" else "Listening")
                val audio = Channel<ShortArray>(capacity = 500)
                listener.set(audio)
                val heard = try {
                    stt.listen(audio, MicCapture.SAMPLE_RATE, language = null, onPartial = { partial ->
                        _state.update { it.copy(partialTranscript = partial) }
                    })
                } finally {
                    listener.set(null)
                    audio.close()
                }
                step("Heard: ${if (heard is OnDeviceStt.Result.Text) EventLog.content(heard.text) else heard}")
                PcmPlayer.play(Earcons.done(sounds, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, usage)
                when (heard) {
                    is OnDeviceStt.Result.Text -> heard.text
                    // Silence after a question just ends the turn.
                    OnDeviceStt.Result.NoSpeech -> return if (followUp) Unit else speak("Sorry, I didn't catch that.", usage)
                    is OnDeviceStt.Result.Failed -> return speak(heard.message, usage, error = heard.message)
                }
            }
            _state.value = AssistantState(phase = Phase.THINKING, transcript = text)

            val reply = thinkAndSpeak(text, transport, config.model, usage, afterTurn, step) ?: return
            followUp = when {
                reply.interrupted -> true.also { step("Interrupted") }
                reply.text.trimEnd().endsWith('?') -> true.also { step("Reply asks a question: listening for the answer") }
                else -> return
            }
        }
    }

    private class Reply(val text: String, val interrupted: Boolean)

    /**
     * Runs the agent and speaks its reply sentence by sentence while it streams in. A trigger
     * during speech stops it; the agent still finishes so its tool results and after-turn
     * actions aren't lost. With no [usage] the reply is only shown. Returns null if the model
     * couldn't be reached (already reported).
     */
    private suspend fun thinkAndSpeak(
        text: String,
        transport: OpenAiChatCompletions,
        model: String,
        usage: PlaybackUsage?,
        afterTurn: MutableList<AfterTurnAction>,
        step: (String) -> Unit,
    ): Reply? = coroutineScope {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val chunker = SentenceChunker()
        val shown = StringBuilder()
        var interrupted = false
        while (interrupts.tryReceive().isSuccess) Unit // drop presses from before this reply

        val speaking = launch { if (usage != null) tts.speakAll(sentences, usage) }
        val watcher = launch {
            interrupts.receive()
            interrupted = true
            interruptible = false
            sentences.close()
            speaking.cancel()
        }
        fun enqueue(sentence: String) {
            if (interrupted || usage == null) return
            if (_state.value.phase != Phase.SPEAKING) {
                step("First sentence ready")
                _state.update { it.copy(phase = Phase.SPEAKING) }
                interruptible = true
            }
            sentences.trySend(sentence)
        }

        try {
            settingsReady?.invoke()
            val result = try {
                agent.respond(
                    text,
                    transport,
                    model,
                    onToolCall = { call -> _state.update { it.copy(actions = it.actions + call.name) } },
                    onText = { delta ->
                        shown.append(delta)
                        _state.update { it.copy(reply = shown.toString().trim()) }
                        chunker.add(delta).forEach(::enqueue)
                    },
                    // A confirmation written alongside a tool call that didn't go through.
                    // A one-sentence confirmation is still in the chunker, so nothing was said.
                    onCard = { card -> _state.update { it.copy(cards = it.cards + card) } },
                    onDiscardText = {
                        shown.setLength(0)
                        chunker.clear()
                        _state.update { it.copy(reply = null) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step("Agent failed: $e")
                interruptible = false
                sentences.close()
                speaking.join()
                val reason = (e as? ProviderException)?.message ?: e.message ?: e::class.simpleName
                speak("I couldn't reach the model.", usage, error = reason)
                return@coroutineScope null
            }
            val (now, later) = result.afterTurn.partition { it.duringReply }
            afterTurn += later
            if (now.isNotEmpty()) {
                // Silent actions (opening an app, starting navigation) start with the reply.
                step("Starting with the reply: ${EventLog.content(now.joinToString { it.description })}")
                _dismissRequests.tryEmit(Unit)
                scope.launch { runAfterTurn(now) }
            }
            step("Reply: ${EventLog.content(result.reply)}")
            chunker.flush()?.let(::enqueue)
            if (shown.isBlank()) {
                val fallback = result.reply.ifBlank { "Done." }
                _state.update { it.copy(reply = fallback) }
                enqueue(fallback)
            }
            sentences.close()
            speaking.join()
            Reply(result.reply, interrupted)
        } finally {
            interruptible = false
            watcher.cancel()
        }
    }

    /** The headset's own choice of listening sounds (e.g. alerting for a helmet intercom), else the default. */
    private fun earconStyle(config: AppSettings, headset: BluetoothDevice?): EarconStyle {
        val address = headset?.let { runCatching { it.address }.getOrNull() }
        return address?.let { config.headsetSounds[it] } ?: config.defaultSounds
    }

    /** Logs when the headset drops the HFP audio link mid-turn, e.g. its button ending voice recognition. Polls; run it off Main. */
    private suspend fun watchHfpLink(route: AudioRouter.Route, step: (String) -> Unit) {
        if (!route.isBluetooth) return
        var wasUp = false
        while (true) {
            val up = router.isHfpAudioUp(route) ?: return
            if (wasUp && !up) step("HFP link dropped mid-turn (phase ${_state.value.phase})")
            wasUp = up
            delay(100)
        }
    }

    /** Says [text], or with no [usage] only shows it. */
    private suspend fun speak(text: String, usage: PlaybackUsage?, error: String? = null) {
        if (usage == null) return _state.update { it.copy(reply = text, error = error) }
        _state.update { it.copy(phase = Phase.SPEAKING, reply = text, error = error) }
        tts.speak(text, usage)
    }

    private suspend fun runAfterTurn(actions: List<AfterTurnAction>) {
        for (action in actions) {
            if (action.needsUnlock && keyguardManager.isKeyguardLocked) {
                val dismiss = keyguardDismisser
                val unlocked = dismiss != null && dismiss()
                if (!unlocked) {
                    EventLog.log("turn", "Skipped '${EventLog.content(action.description)}': device locked")
                    _state.update { it.copy(error = "Unlock your phone to ${action.description}.") }
                    continue
                }
            }
            try {
                action.run()
                EventLog.log("turn", "After turn: ${EventLog.content(action.description)}")
            } catch (e: Exception) {
                EventLog.log("turn", "After turn '${EventLog.content(action.description)}' failed: ${e::class.simpleName}")
                _state.update { it.copy(error = "Couldn't ${action.description}: ${e.message}") }
            }
        }
    }

    private companion object {
        /** Listen/speak exchanges in one turn before Orbit stops listening for follow-ups. */
        const val MAX_EXCHANGES = 5

        /** Repeats of the same trigger closer together than this are one press delivered twice. */
        const val DUPLICATE_TRIGGER_MS = 500L
    }
}
