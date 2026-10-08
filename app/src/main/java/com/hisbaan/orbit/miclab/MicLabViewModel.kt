package com.hisbaan.orbit.miclab

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisbaan.orbit.OrbitApp
import com.hisbaan.orbit.audio.AudioFocus
import com.hisbaan.orbit.audio.AudioMonitor
import com.hisbaan.orbit.audio.AudioRouter
import com.hisbaan.orbit.audio.CaptureBuffer
import com.hisbaan.orbit.audio.CaptureSource
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.audio.FocusMode
import com.hisbaan.orbit.audio.MicCapture
import com.hisbaan.orbit.audio.PcmPlayer
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.audio.RouteStrategy
import com.hisbaan.orbit.audio.audioModeName
import com.hisbaan.orbit.audio.describe
import com.hisbaan.orbit.audio.label
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class LabSettings(
    val strategy: RouteStrategy = RouteStrategy.HFP_VOICE_RECOGNITION,
    val setCommunicationMode: Boolean = false,
    val source: CaptureSource = CaptureSource.VOICE_RECOGNITION,
    /** AudioDeviceInfo ids are not stable across reconnects, so this is not persisted. */
    val preferredInputId: Int? = null,
    val usage: PlaybackUsage = PlaybackUsage.VOICE_COMMUNICATION,
    val focusMode: FocusMode = FocusMode.TRANSIENT_EXCLUSIVE,
    /** Hold focus until the HFP link is really down, so media doesn't resume over SCO. */
    val releaseFocusAfterLinkDown: Boolean = true,
)

data class DeviceSnapshot(
    val audioMode: String = "",
    val communicationDevice: String = "none",
    val hfpDevices: List<String> = emptyList(),
    val inputs: List<AudioDeviceInfo> = emptyList(),
    val outputs: List<String> = emptyList(),
    val communicationCandidates: List<String> = emptyList(),
)

data class MicLabState(
    val settings: LabSettings = LabSettings(),
    val missingPermissions: List<String> = emptyList(),
    val isDefaultAssistant: Boolean = false,
    val devices: DeviceSnapshot = DeviceSnapshot(),
    val route: String? = null,
    val recording: Boolean = false,
    val rmsDbfs: Float = Float.NEGATIVE_INFINITY,
    val peakDbfs: Float = Float.NEGATIVE_INFINITY,
    val lastRecording: String? = null,
    val busy: String? = null,
    val ttsText: String = "Orbit here. If you can hear this in your helmet, output routing works.",
)

class MicLabViewModel(app: Application) : AndroidViewModel(app) {
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val prefs = app.getSharedPreferences("miclab", Context.MODE_PRIVATE)
    // The app's, shared with the assistant: one HFP proxy and one TTS engine.
    private val headsetProfile = (app as OrbitApp).headsetProfile
    private val router = AudioRouter(audioManager, headsetProfile)
    private val monitor = AudioMonitor(app, audioManager) { refresh() }
    private val tts = (app as OrbitApp).tts
    private val focus = AudioFocus(audioManager)

    private val _state = MutableStateFlow(MicLabState(settings = loadSettings()))
    val state: StateFlow<MicLabState> = _state.asStateFlow()

    private var route: AudioRouter.Route? = null
    private var recordingSamples: ShortArray? = null
    private var recordJob: Job? = null
    private val stopRecording = AtomicBoolean(false)
    private var hailJob: Job? = null

    init {
        monitor.start()
        // Bind the HFP proxy early so a headset trigger doesn't pay for it.
        viewModelScope.launch {
            headsetProfile.get()
            refresh()
        }
        refresh()
    }

    override fun onCleared() {
        recordJob?.cancel()
        hailJob?.cancel()
        route?.let(router::release)
        focus.release()
        monitor.stop()
    }

    // region Settings

    fun updateSettings(transform: (LabSettings) -> LabSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        saveSettings(_state.value.settings)
    }

    fun setTtsText(text: String) = _state.update { it.copy(ttsText = text) }

    private fun loadSettings() = LabSettings(
        strategy = enumOr(prefs.getString("strategy", null), LabSettings().strategy),
        setCommunicationMode = prefs.getBoolean("setCommunicationMode", false),
        source = enumOr(prefs.getString("source", null), LabSettings().source),
        usage = enumOr(prefs.getString("usage", null), LabSettings().usage),
        focusMode = enumOr(prefs.getString("focusMode", null), LabSettings().focusMode),
        releaseFocusAfterLinkDown = prefs.getBoolean("releaseFocusAfterLinkDown", true),
    )

    private fun saveSettings(s: LabSettings) = prefs.edit {
        putString("strategy", s.strategy.name)
        putBoolean("setCommunicationMode", s.setCommunicationMode)
        putString("source", s.source.name)
        putString("usage", s.usage.name)
        putString("focusMode", s.focusMode.name)
        putBoolean("releaseFocusAfterLinkDown", s.releaseFocusAfterLinkDown)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    // endregion

    fun refresh() {
        val app = getApplication<Application>()
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
        }
        val roleManager = app.getSystemService(RoleManager::class.java)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val devices = DeviceSnapshot(
            audioMode = audioModeName(audioManager.mode),
            communicationDevice = audioManager.communicationDevice?.describe() ?: "none",
            hfpDevices = headsetProfile.connectedDevices().map {
                "${it.label()} audio=${headsetProfile.isAudioConnected(it)}"
            },
            inputs = inputs,
            outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.describe() },
            communicationCandidates = audioManager.availableCommunicationDevices.map { it.describe() },
        )
        _state.update {
            it.copy(
                missingPermissions = missing,
                isDefaultAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
                devices = devices,
                settings = it.settings.copy(
                    preferredInputId = it.settings.preferredInputId?.takeIf { id -> inputs.any { d -> d.id == id } },
                ),
            )
        }
    }

    // region Manual controls

    fun toggleRoute() = launchBusy("route") {
        val current = route
        val s = _state.value.settings
        if (current != null) {
            releaseRouteAndFocus(current, s)
            route = null
        } else {
            focus.acquire(s.focusMode)
            route = router.acquire(s.strategy, s.setCommunicationMode)
        }
        _state.update { it.copy(route = route?.summary()) }
        refresh()
    }

    fun toggleRecording() {
        if (recordJob?.isActive == true) {
            stopRecording.set(true)
            return
        }
        if (!hasPermissions()) return
        recordJob = viewModelScope.launch {
            val buffer = CaptureBuffer(maxMs = 30_000)
            record(buffer, maxMs = 30_000)
            recordingSamples = buffer.copy()
        }
    }

    fun playRecording() = launchBusy("playback") {
        val samples = recordingSamples ?: return@launchBusy EventLog.log("lab", "Nothing recorded yet")
        PcmPlayer.play(samples, MicCapture.SAMPLE_RATE, _state.value.settings.usage)
    }

    fun playBeep() = launchBusy("beep") {
        PcmPlayer.play(PcmPlayer.beep(MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, _state.value.settings.usage)
    }

    fun speak() = launchBusy("tts") {
        tts.speak(_state.value.ttsText, _state.value.settings.usage)
    }

    fun clearLog() = EventLog.clear()

    // endregion

    // region Hail test

    /**
     * The end-to-end check: route → start capture → beep → record 5 s → play back → release,
     * with the elapsed time logged at each step. Uses the currently selected settings so the
     * same matrix can be run from the headset button.
     *
     * One capture stream stays open for the whole test: the SCO link is only up while some
     * stream is active, and letting it drop between steps clips the start of each sound.
     */
    fun runHailTest(trigger: String, device: BluetoothDevice? = null) {
        if (hailJob?.isActive == true) {
            EventLog.log("hail", "Already running; ignoring trigger $trigger")
            return
        }
        hailJob = viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            fun step(message: String) = EventLog.log("hail", "+${SystemClock.elapsedRealtime() - t0}ms $message")

            if (!hasPermissions()) {
                step("Aborted: missing ${_state.value.missingPermissions.joinToString()}")
                return@launch
            }
            stopRecording.set(true)
            recordJob?.join()
            route?.let(router::release)
            route = null

            val s = _state.value.settings
            step(
                "Start trigger=$trigger device=${device?.label() ?: "?"} strategy=${s.strategy.label} " +
                    "source=${s.source.label} usage=${s.usage.label}",
            )
            _state.update { it.copy(busy = "hail test") }
            // Focus first, so media pauses before the link opens instead of playing through it.
            focus.acquire(s.focusMode)
            val acquired = router.acquire(s.strategy, s.setCommunicationMode, device)
            route = acquired
            _state.update { it.copy(route = acquired.summary()) }
            val buffer = CaptureBuffer(maxMs = 30_000)
            try {
                step("Route ready: ${acquired.summary()}; musicActive=${audioManager.isMusicActive}")
                val capture = async { record(buffer, maxMs = 30_000) }
                val linkMs = router.awaitHfpAudio(acquired, timeoutMs = 2_000)
                step(linkMs?.let { "HFP audio link up ${it}ms after capture start" } ?: "HFP audio link not up")
                PcmPlayer.play(PcmPlayer.beep(MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, s.usage)
                val start = buffer.size
                step("Beep played; recording 5s, speak now")
                delay(5_000)
                val clip = buffer.copy(from = start)
                recordingSamples = clip
                step("Captured ${clip.size * 1000L / MicCapture.SAMPLE_RATE}ms; playing back with capture still open")
                PcmPlayer.play(clip, MicCapture.SAMPLE_RATE, s.usage)
                step("Playback done")
                stopRecording.set(true)
                capture.await()
            } finally {
                stopRecording.set(true)
                releaseRouteAndFocus(acquired, s)
                route = null
                _state.update { it.copy(route = null, busy = null) }
                step("Released. Total ${SystemClock.elapsedRealtime() - t0}ms")
                refresh()
            }
        }
    }

    // endregion

    /**
     * Releases the route, optionally waits for the HFP link to actually drop, then abandons
     * focus so paused media resumes on A2DP rather than briefly over SCO.
     */
    private suspend fun releaseRouteAndFocus(route: AudioRouter.Route, s: LabSettings) {
        router.release(route)
        if (s.releaseFocusAfterLinkDown) {
            val downMs = router.awaitHfpAudioDown(route, timeoutMs = 3_000)
            EventLog.log("route", downMs?.let { "HFP audio link down ${it}ms after release" } ?: "HFP audio link still up after 3000ms")
        }
        focus.release()
        // Media apps resume asynchronously; check whether it actually came back.
        viewModelScope.launch {
            delay(1_500)
            EventLog.log("focus", "1.5s after abandoning focus: musicActive=${audioManager.isMusicActive}")
        }
    }

    /** Records into [buffer], stopping on [stopRecording] or after [maxMs]. */
    private suspend fun record(buffer: CaptureBuffer, maxMs: Long): MicCapture.Result {
        val s = _state.value.settings
        val preferred = s.preferredInputId?.let { id -> _state.value.devices.inputs.firstOrNull { it.id == id } }
        stopRecording.set(false)
        _state.update { it.copy(recording = true) }
        var chunk = 0
        try {
            val maxSamples = MicCapture.SAMPLE_RATE * maxMs / 1000
            val result = MicCapture.record(s.source, preferred, stopRecording) { samples, count, rms, peak ->
                buffer.append(samples, count)
                if (buffer.size >= maxSamples) stopRecording.set(true)
                if (chunk++ % 3 == 0) _state.update { it.copy(rmsDbfs = rms, peakDbfs = peak) }
            }
            _state.update {
                it.copy(
                    lastRecording = "${result.durationMs}ms from ${result.routedDevice?.describe() ?: "?"}, " +
                        "max RMS ${"%.1f".format(result.maxRmsDbfs)} dBFS",
                )
            }
            return result
        } finally {
            _state.update { it.copy(recording = false, rmsDbfs = Float.NEGATIVE_INFINITY, peakDbfs = Float.NEGATIVE_INFINITY) }
        }
    }

    private fun hasPermissions(): Boolean {
        refresh()
        val missing = _state.value.missingPermissions
        if (missing.isNotEmpty()) EventLog.log("lab", "Missing permissions: ${missing.joinToString()}")
        return missing.isEmpty()
    }

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) {
            EventLog.log("lab", "Busy with ${_state.value.busy}; ignoring $label")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = label) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun logHeader(): String {
        val s = _state.value.settings
        return "Orbit Mic Lab — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "strategy=${s.strategy.label} setMode=${s.setCommunicationMode} source=${s.source.label} usage=${s.usage.label} " +
            "focus=${s.focusMode.label} focusAfterLinkDown=${s.releaseFocusAfterLinkDown}"
    }

    companion object {
        val REQUIRED_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
