package com.hisbaan.orbit.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.hisbaan.orbit.diagnostics.EventLog

/**
 * Registers Orbit as a "Digital assistant app" candidate. Bound by the system while Orbit
 * is the selected assistant, which also exempts it from background activity start limits.
 */
class OrbitVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Orbit doesn't read the screen; skipping the assist data and screenshot makes the
        // overlay appear sooner and avoids the screenshot flash.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        active = this
        EventLog.log("assist", "VoiceInteractionService ready")
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        EventLog.log("assist", "Launched from the lock screen")
        showSession(OrbitSession.args("lock screen", null), 0)
    }

    override fun onShutdown() {
        EventLog.log("assist", "VoiceInteractionService shut down")
        if (active === this) active = null
        super.onShutdown()
    }

    companion object {
        /** The bound service while Orbit is the selected assistant. */
        @Volatile
        private var active: OrbitVoiceInteractionService? = null

        /** Shows the overlay, which starts a turn. False if Orbit isn't the active assistant. */
        fun showOverlay(args: Bundle): Boolean {
            val service = active ?: return false
            service.showSession(args, 0)
            return true
        }
    }
}
