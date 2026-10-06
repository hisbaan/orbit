package com.hisbaan.orbit.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible activity that asks the user to unlock (fingerprint, face or PIN), for after-turn
 * actions that open another app. `requestDismissKeyguard` needs an activity, and with the
 * overlay there is no Orbit activity on screen otherwise.
 */
class UnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = done(true)
                override fun onDismissCancelled() = done(false)
                override fun onDismissError() = done(false)
            },
        )
    }

    override fun onDestroy() {
        pending?.complete(false)
        super.onDestroy()
    }

    private fun done(unlocked: Boolean) {
        EventLog.log("unlock", if (unlocked) "Unlocked" else "Unlock cancelled")
        pending?.complete(unlocked)
        finish()
    }

    companion object {
        @Volatile
        private var pending: CompletableDeferred<Boolean>? = null

        /** Shows the unlock prompt; true once the device is unlocked. */
        suspend fun request(context: Context): Boolean {
            val result = CompletableDeferred<Boolean>()
            pending?.complete(false)
            pending = result
            context.startActivity(
                Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            return withTimeoutOrNull(60_000) { result.await() } ?: false
        }
    }
}
