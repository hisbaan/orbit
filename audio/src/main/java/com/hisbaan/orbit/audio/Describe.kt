package com.hisbaan.orbit.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Human-readable names for the platform's integer constants, for the event log and UI. */

fun AudioDeviceInfo.describe(): String {
    val addr = address.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return "${deviceTypeName(type)} '$productName'$addr #$id"
}

fun deviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "SPEAKER_SAFE"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
    else -> "type$type"
}

fun isBluetoothVoiceDevice(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

fun audioModeName(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
    AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
    AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
    else -> "mode$mode"
}

@Suppress("DEPRECATION")
fun scoStateName(state: Int): String = when (state) {
    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
    AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
    AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
    AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
    else -> "state$state"
}

fun hfpAudioStateName(state: Int): String = when (state) {
    BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "AUDIO_DISCONNECTED"
    BluetoothHeadset.STATE_AUDIO_CONNECTING -> "AUDIO_CONNECTING"
    BluetoothHeadset.STATE_AUDIO_CONNECTED -> "AUDIO_CONNECTED"
    else -> "state$state"
}

fun profileStateName(state: Int): String = when (state) {
    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
    else -> "state$state"
}

/** Device name needs BLUETOOTH_CONNECT; fall back to the address without it. */
@SuppressLint("MissingPermission")
fun BluetoothDevice.label(): String = try {
    "'${name ?: "?"}' $address"
} catch (_: SecurityException) {
    address
}
