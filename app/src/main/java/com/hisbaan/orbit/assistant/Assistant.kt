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
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.audio.AudioFocus
import com.hisbaan.orbit.audio.AudioRouter
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.audio.HeadsetProfile
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.settings.AppSettings
import com.hisbaan.orbit.settings.SettingsRepository
import com.hisbaan.orbit.settings.chatTransport
import com.hisbaan.orbit.speech.OnDeviceStt
import com.hisbaan.orbit.speech.TtsSpeaker
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs turns: starts, interrupts and cancels them, and runs their after-turn actions. A voice
 * turn opens its audio ([TurnAudio]), talks ([Conversation]), releases the audio, then runs
 * the actions that need the headset released (music, calls).
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

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Emitted before after-turn actions run, so the overlay gets out of the way of the app they open. */
    private val _dismissRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissRequests: SharedFlow<Unit> = _dismissRequests.asSharedFlow()

    /** A turn that fails ends with an error on screen instead of taking the app down. */
    private val failures = CoroutineExceptionHandler { _, e ->
        EventLog.log("turn", "Failed: $e")
        _state.update { it.copy(phase = Phase.IDLE, error = "Something went wrong: ${e.message ?: e::class.simpleName}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failures)
    private var turn: Job? = null
    private var lastTriggerSource: String? = null
    private var lastTriggerAt = 0L

    private val conversation = Conversation(Agent(tools, systemPrompt = { SystemPrompt.build() }), _state) { now ->
        _dismissRequests.tryEmit(Unit)
        scope.launch { runAfterTurn(now) }
    }

    /**
     * Asks the user to unlock before an after-turn action opens another app's UI. Returns
     * true if the device is unlocked afterwards.
     */
    @Volatile
    var keyguardDismisser: (suspend () -> Boolean)? = null

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
            if (conversation.interrupt()) {
                EventLog.log("turn", "Trigger from $source while speaking: interrupting to listen")
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
        val step = stepLog()
        step("Start: trigger=$source device=${device?.address ?: "?"}")
        _state.value = AssistantState(phase = Phase.STARTING)

        val audio = TurnAudio(focus, router, stt, tts, scope, phase = { _state.value.phase }, log = step)
        val afterTurn = try {
            val config = settings.current()
            val mic = hasMicPermission()
            audio.open(device, captureMic = mic, voiceName = config.ttsVoice) { headset -> earconStyle(config, headset) }
            when {
                !mic -> emptyList<AfterTurnAction>().also {
                    conversation.say(audio, "Orbit needs microphone permission. Open Orbit to grant it.", error = "Microphone permission missing")
                }
                !config.isProviderConfigured -> emptyList<AfterTurnAction>().also { conversation.say(audio, NOT_SET_UP) }
                else -> conversation.talk(audio, config.chatTransport(httpClient), config.model, typed, step)
            }
        } finally {
            withContext(NonCancellable) {
                _state.update { it.copy(phase = Phase.FINISHING) }
                audio.close()
                _state.update { it.copy(phase = Phase.IDLE) }
                step("Turn finished")
            }
        }
        if (afterTurn.isNotEmpty()) _dismissRequests.tryEmit(Unit)
        runAfterTurn(afterTurn)
    }

    private suspend fun runTextTurn(text: String) {
        val step = stepLog()
        step("Start: typed")
        _state.value = AssistantState(phase = Phase.THINKING, transcript = text)
        val afterTurn = try {
            val config = settings.current()
            if (config.isProviderConfigured) {
                conversation.answer(text, config.chatTransport(httpClient), config.model, step)
            } else {
                emptyList<AfterTurnAction>().also { conversation.say(voice = null, NOT_SET_UP) }
            }
        } finally {
            _state.update { it.copy(phase = Phase.IDLE) }
            step("Turn finished")
        }
        if (afterTurn.isNotEmpty()) _dismissRequests.tryEmit(Unit)
        runAfterTurn(afterTurn)
    }

    /** A turn's step log: each line stamped with the time since the turn started. */
    private fun stepLog(): (String) -> Unit {
        val t0 = SystemClock.elapsedRealtime()
        return { EventLog.log("turn", "+${SystemClock.elapsedRealtime() - t0}ms $it") }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The headset's own choice of listening sounds (e.g. alerting for a helmet intercom), else the default. */
    private fun earconStyle(config: AppSettings, headset: BluetoothDevice?): EarconStyle {
        val address = headset?.let { runCatching { it.address }.getOrNull() }
        return address?.let { config.headsetSounds[it] } ?: config.defaultSounds
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("turn", "After turn '${EventLog.content(action.description)}' failed: ${e::class.simpleName}")
                _state.update { it.copy(error = "Couldn't ${action.description}: ${e.message}") }
            }
        }
    }

    private companion object {
        /** Repeats of the same trigger closer together than this are one press delivered twice. */
        const val DUPLICATE_TRIGGER_MS = 500L

        const val NOT_SET_UP = "Orbit isn't set up yet. Add a provider and model in Orbit's settings."
    }
}
