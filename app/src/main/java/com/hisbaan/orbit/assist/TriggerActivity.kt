package com.hisbaan.orbit.assist

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.hisbaan.orbit.MainActivity
import com.hisbaan.orbit.OrbitApp
import com.hisbaan.orbit.audio.label
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.launch

/**
 * Invisible entry point for intent-based triggers: the Bluetooth stack launches VOICE_COMMAND
 * when a headset's assistant button sends AT+BVRA=1 (VOICE_SEARCH_HANDS_FREE when locked).
 * Hands off to the overlay and finishes. Falls back to the full app when Orbit isn't the
 * active assistant, or when the debug hail-test switch is on.
 */
class TriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val trigger = intent
        val action = trigger.action ?: return finish()
        val device = IntentCompat.getParcelableExtra(trigger, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val extras = trigger.extras?.keySet()?.joinToString().orEmpty()
        EventLog.log(
            "trigger",
            "Launched by $action${if (extras.isNotEmpty()) " extras=[$extras]" else ""}" + (device?.let { " device=${it.label()}" } ?: ""),
        )
        val source = action.substringAfterLast('.')
        lifecycleScope.launch {
            val hailTest = (application as OrbitApp).settings.current().triggerRunsHailTest
            if (hailTest || !OrbitVoiceInteractionService.showOverlay(OrbitSession.args(source, device))) {
                if (!hailTest) EventLog.log("trigger", "Orbit isn't the active assistant: opening the app instead")
                startActivity(Intent(trigger).setClass(this@TriggerActivity, MainActivity::class.java))
            }
            finish()
        }
    }
}
