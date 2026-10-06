package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Logs every audio-routing and Bluetooth-voice signal the platform gives us, so a failed
 * run can be read back step by step. [onChange] fires whenever the device picture may
 * have changed, so the UI can refresh.
 */
@Suppress("DEPRECATION") // SCO broadcasts are deprecated in API 37 but still useful diagnostics.
class AudioMonitor(
    context: Context,
    private val audioManager: AudioManager,
    private val onChange: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val who = device?.label()?.let { " $it" } ?: ""
            when (intent.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val prev = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -2)
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -2)
                    EventLog.log("sco", "SCO ${scoStateName(prev)} -> ${scoStateName(state)}")
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    EventLog.log("hfp", "Audio ${hfpAudioStateName(prev)} -> ${hfpAudioStateName(state)}$who")
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    EventLog.log("hfp", "Connection ${profileStateName(prev)} -> ${profileStateName(state)}$who")
                }
            }
            onChange()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.forEach { EventLog.log("dev", "+ ${if (it.isSource) "in" else "out"} ${it.describe()}") }
            onChange()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            removed.forEach { EventLog.log("dev", "- ${if (it.isSource) "in" else "out"} ${it.describe()}") }
            onChange()
        }
    }

    private val communicationDeviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        EventLog.log("comm", "Communication device -> ${device?.describe() ?: "none"}")
        onChange()
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        EventLog.log("mode", "Audio mode -> ${audioModeName(mode)}")
        onChange()
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val summary = configs.joinToString("; ") {
                "session=${it.clientAudioSessionId} src=${it.clientAudioSource} " +
                    "dev=${it.audioDevice?.describe() ?: "?"} silenced=${it.isClientSilenced}"
            }
            EventLog.log("rec", "Active recordings: ${summary.ifEmpty { "none" }}")
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        // Sent by the Bluetooth process rather than the system server, so must be exported.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        audioManager.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, communicationDeviceListener)
        audioManager.addOnModeChangedListener(appContext.mainExecutor, modeListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
    }

    fun stop() {
        if (!started) return
        started = false
        appContext.unregisterReceiver(receiver)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
        audioManager.removeOnModeChangedListener(modeListener)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
    }
}
