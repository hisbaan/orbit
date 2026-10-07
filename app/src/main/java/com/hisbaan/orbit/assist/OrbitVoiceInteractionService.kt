package com.hisbaan.orbit.assist

import android.os.Build
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
        // Screen text and screenshots feed read_screen. Cleared explicitly: the system keeps
        // the value an earlier version set (it disabled both) until the service changes it.
        // On Android 17 the system also needs the usesAssist* declarations in
        // res/xml/voice_interaction_service.xml, or it strips the request flags.
        setDisabledShowContext(0)
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
        /** Ask for the screen's text and a screenshot; the content flag is new in Android 17. */
        private val SCREEN_CONTEXT = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
            (if (Build.VERSION.SDK_INT >= 37) VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT else 0)

        /** The bound service while Orbit is the selected assistant. */
        @Volatile
        private var active: OrbitVoiceInteractionService? = null

        /** Shows the overlay, which starts a turn. False if Orbit isn't the active assistant. */
        fun showOverlay(args: Bundle): Boolean {
            val service = active ?: return false
            // Screen text and a screenshot for read_screen, if the user allows it.
            service.showSession(args, SCREEN_CONTEXT)
            return true
        }
    }
}
