package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Opening the SCO link does not pause media: without a focus request, music keeps playing
 * and gets squeezed through the narrow-band voice link. Transient focus tells the media app
 * to pause and to resume when we abandon it.
 */
enum class FocusMode(val label: String, val gain: Int?) {
    TRANSIENT_EXCLUSIVE("GAIN_TRANSIENT_EXCLUSIVE", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE),
    TRANSIENT("GAIN_TRANSIENT", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
    MAY_DUCK("GAIN_TRANSIENT_MAY_DUCK", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK),
    OFF("Off", null),
}

class AudioFocus(private val audioManager: AudioManager) {
    private var request: AudioFocusRequest? = null

    fun acquire(mode: FocusMode): Boolean {
        release()
        val gain = mode.gain ?: return false
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(
                { change -> EventLog.log("focus", "Focus changed: ${focusChangeName(change)}") },
                Handler(Looper.getMainLooper()),
            )
            .build()
        val result = audioManager.requestAudioFocus(req)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        EventLog.log(
            "focus",
            "requestAudioFocus(${mode.label}) = ${if (granted) "GRANTED" else "result $result"}; " +
                "musicActive=${audioManager.isMusicActive}",
        )
        if (granted) request = req
        return granted
    }

    fun release() {
        val req = request ?: return
        audioManager.abandonAudioFocusRequest(req)
        request = null
        EventLog.log("focus", "Abandoned focus")
    }

    private fun focusChangeName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "change$change"
    }
}
