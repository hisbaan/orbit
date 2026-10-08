package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The different ways to get a Bluetooth headset's mic routed to us. See PLAN.md §1. */
enum class RouteStrategy(val label: String) {
    /**
     * The production choice (PLAN.md §1): HFP voice recognition when an HFP headset is
     * connected; otherwise, or if that fails, `setCommunicationDevice` (covers LE Audio);
     * otherwise no routing. The returned [AudioRouter.Route] records what was actually used.
     */
    AUTO("Auto (HFP VR → communication device)"),
    NONE("None (no routing)"),
    HFP_VOICE_RECOGNITION("HFP startVoiceRecognition"),
    COMMUNICATION_DEVICE("setCommunicationDevice"),
    /** Broken on Pixel 10 Pro + Packtalk Edge: the link drops when the first stream starts. */
    HFP_PLUS_COMMUNICATION_DEVICE("HFP VR + setCommunicationDevice"),
    LEGACY_SCO("Legacy startBluetoothSco"),
}

/**
 * Opens and closes the Bluetooth voice link using one of the [RouteStrategy] variants, and
 * measures how long the link takes to come up.
 *
 * Two stages, observed on Pixel 10 Pro / Android 17: first the audio policy switches the
 * communication device to the headset ("route ready", ~200-400 ms). The HFP SCO audio link
 * itself only connects once a stream is actually playing or recording (~150 ms more), and
 * drops again ~40 ms after the last stream stops. Callers should keep a stream open for as
 * long as they want the link, and use [awaitHfpAudio] once one is running.
 */
class AudioRouter(
    private val audioManager: AudioManager,
    private val headsetProfile: HeadsetProfile,
) {
    class Route(
        val strategy: RouteStrategy,
        val headset: BluetoothHeadset?,
        val hfpDevice: BluetoothDevice?,
        val communicationDevice: AudioDeviceInfo?,
        val previousMode: Int,
        val changedMode: Boolean,
        /** Ms from acquire() until the communication device was the headset; null if never. */
        val routeReadyMs: Long?,
    ) {
        /** Audio is going to a Bluetooth headset (vs. the phone's own mic and speaker). */
        val isBluetooth: Boolean get() = strategy != RouteStrategy.NONE && routeReadyMs != null

        fun summary(): String = buildString {
            append(strategy.label)
            hfpDevice?.let { append(", hfp=${it.label()}") }
            communicationDevice?.let { append(", comm=${it.describe()}") }
            if (changedMode) append(", mode=IN_COMMUNICATION")
            append(", ready=${routeReadyMs?.let { "${it}ms" } ?: "not confirmed"}")
        }
    }

    /**
     * [preferredDevice] is the headset that triggered us (from the VOICE_COMMAND intent), if
     * known; otherwise the first connected HFP device is used.
     *
     * Cancellation-safe: cancelled (or failing) after the route was requested, while waiting for
     * it to come up, it releases what it set up before rethrowing, so a caller that never got the
     * [Route] has nothing to undo.
     */
    suspend fun acquire(
        strategy: RouteStrategy,
        setCommunicationMode: Boolean,
        preferredDevice: BluetoothDevice? = null,
    ): Route {
        val t0 = SystemClock.elapsedRealtime()
        EventLog.log("route", "Acquire: ${strategy.label}, setMode=$setCommunicationMode")

        val headset = if (strategy == RouteStrategy.NONE) null else headsetProfile.get()
        val hfpDevices = headsetProfile.connectedDevices()
        val hfpDevice = hfpDevices.firstOrNull { it.address == preferredDevice?.address } ?: hfpDevices.firstOrNull()
        if (strategy != RouteStrategy.NONE) {
            EventLog.log(
                "route",
                "HFP devices: ${hfpDevices.joinToString { it.label() }.ifEmpty { "none" }} " +
                    "(proxy ready +${SystemClock.elapsedRealtime() - t0}ms)",
            )
        }

        val previousMode = audioManager.mode
        val changeMode = setCommunicationMode && strategy != RouteStrategy.NONE
        if (changeMode) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            EventLog.log("route", "mode ${audioModeName(previousMode)} -> ${audioModeName(audioManager.mode)}")
        }

        var communicationDevice: AudioDeviceInfo? = null
        var used = strategy
        when (strategy) {
            RouteStrategy.AUTO -> used = when {
                hfpDevice != null && startVoiceRecognition(headset, hfpDevice) -> RouteStrategy.HFP_VOICE_RECOGNITION
                else -> {
                    communicationDevice = setCommunicationDevice(hfpDevice)
                    if (communicationDevice != null) RouteStrategy.COMMUNICATION_DEVICE else RouteStrategy.NONE
                }
            }.also { EventLog.log("route", "Auto resolved to ${it.label}") }
            RouteStrategy.NONE -> Unit
            RouteStrategy.HFP_VOICE_RECOGNITION -> startVoiceRecognition(headset, hfpDevice)
            RouteStrategy.COMMUNICATION_DEVICE -> communicationDevice = setCommunicationDevice(hfpDevice)
            RouteStrategy.HFP_PLUS_COMMUNICATION_DEVICE -> {
                startVoiceRecognition(headset, hfpDevice)
                communicationDevice = setCommunicationDevice(hfpDevice)
            }
            RouteStrategy.LEGACY_SCO -> startLegacySco()
        }

        val routeReadyMs = try {
            if (used == RouteStrategy.NONE) null else awaitRoute(t0)
        } catch (e: Throwable) {
            EventLog.log("route", "Acquire interrupted (${e::class.simpleName}); releasing")
            release(Route(used, headset, hfpDevice, communicationDevice, previousMode, changeMode, routeReadyMs = null))
            throw e
        }
        return Route(used, headset, hfpDevice, communicationDevice, previousMode, changeMode, routeReadyMs)
            .also { EventLog.log("route", "Acquired: ${it.summary()}") }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun release(route: Route) {
        try {
            when (route.strategy) {
                RouteStrategy.AUTO, RouteStrategy.NONE -> Unit
                RouteStrategy.HFP_VOICE_RECOGNITION -> stopVoiceRecognition(route)
                RouteStrategy.COMMUNICATION_DEVICE -> audioManager.clearCommunicationDevice()
                RouteStrategy.HFP_PLUS_COMMUNICATION_DEVICE -> {
                    audioManager.clearCommunicationDevice()
                    stopVoiceRecognition(route)
                }
                RouteStrategy.LEGACY_SCO -> {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
            }
        } catch (e: Exception) {
            EventLog.log("route", "Release failed: $e")
        }
        if (route.changedMode) audioManager.mode = route.previousMode
        EventLog.log("route", "Released ${route.strategy.label}, mode=${audioModeName(audioManager.mode)}")
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecognition(headset: BluetoothHeadset?, device: BluetoothDevice?): Boolean {
        if (headset == null || device == null) {
            EventLog.log("route", "startVoiceRecognition skipped: no HFP proxy/device")
            return false
        }
        val ok = try {
            headset.startVoiceRecognition(device)
        } catch (e: SecurityException) {
            EventLog.log("route", "startVoiceRecognition: missing BLUETOOTH_CONNECT")
            return false
        }
        EventLog.log("route", "startVoiceRecognition(${device.label()}) = $ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun stopVoiceRecognition(route: Route) {
        val device = route.hfpDevice ?: return
        val ok = route.headset?.stopVoiceRecognition(device)
        EventLog.log("route", "stopVoiceRecognition(${device.label()}) = $ok")
    }

    private fun setCommunicationDevice(hfpDevice: BluetoothDevice?): AudioDeviceInfo? {
        val candidates = audioManager.availableCommunicationDevices.filter(::isBluetoothVoiceDevice)
        val target = candidates.firstOrNull { hfpDevice != null && it.address.equals(hfpDevice.address, true) }
            ?: candidates.firstOrNull()
        if (target == null) {
            EventLog.log("route", "setCommunicationDevice skipped: no BT communication device available")
            return null
        }
        val ok = audioManager.setCommunicationDevice(target)
        EventLog.log("route", "setCommunicationDevice(${target.describe()}) = $ok")
        return target.takeIf { ok }
    }

    @Suppress("DEPRECATION")
    private fun startLegacySco() {
        EventLog.log("route", "isBluetoothScoAvailableOffCall=${audioManager.isBluetoothScoAvailableOffCall}")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        EventLog.log("route", "startBluetoothSco() called")
    }

    /**
     * Polls until the audio policy has switched the communication device to a BT headset. Polls
     * run on IO: each is a call into the audio or Bluetooth service, every [POLL_MS].
     */
    private suspend fun awaitRoute(t0: Long): Long? = withContext(Dispatchers.IO) {
        val deadline = t0 + ROUTE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (audioManager.communicationDevice?.let(::isBluetoothVoiceDevice) == true) {
                return@withContext SystemClock.elapsedRealtime() - t0
            }
            delay(POLL_MS)
        }
        EventLog.log("route", "Communication device not a BT headset within ${ROUTE_TIMEOUT_MS}ms")
        null
    }

    /** Whether the route's HFP audio link is up right now; null when the route has no HFP device. */
    fun isHfpAudioUp(route: Route): Boolean? = route.hfpDevice?.let(headsetProfile::isAudioConnected)

    /**
     * Polls until the HFP SCO audio link is connected. Only meaningful while a stream is
     * running (see class doc). Returns ms waited, 0 for LE Audio (no HFP link), null on timeout.
     */
    suspend fun awaitHfpAudio(route: Route, timeoutMs: Long): Long? {
        if (route.strategy == RouteStrategy.NONE) return null
        val device = route.hfpDevice ?: return 0
        return awaitHfpAudioState(device, up = true, timeoutMs)
    }

    /**
     * After [release], polls until the HFP SCO audio link is actually down. The SCO-state
     * broadcast reports DISCONNECTED much earlier than the link really drops. Returns ms waited,
     * 0 if there was no HFP link to wait for, null on timeout.
     */
    suspend fun awaitHfpAudioDown(route: Route, timeoutMs: Long): Long? {
        if (route.strategy == RouteStrategy.NONE) return 0
        val device = route.hfpDevice ?: return 0
        return awaitHfpAudioState(device, up = false, timeoutMs)
    }

    /** Polls (on IO) until [device]'s HFP audio link is [up] or not; ms waited, or null on timeout. */
    private suspend fun awaitHfpAudioState(device: BluetoothDevice, up: Boolean, timeoutMs: Long): Long? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (headsetProfile.isAudioConnected(device) == up) return@withContext SystemClock.elapsedRealtime() - t0
            delay(POLL_MS)
        }
        null
    }

    private companion object {
        const val ROUTE_TIMEOUT_MS = 2000L
        const val POLL_MS = 10L
    }
}
