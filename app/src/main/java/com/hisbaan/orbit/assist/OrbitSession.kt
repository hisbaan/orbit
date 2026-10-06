package com.hisbaan.orbit.assist

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hisbaan.orbit.MainActivity
import com.hisbaan.orbit.OrbitApp
import com.hisbaan.orbit.assistant.Phase
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.ui.OrbitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The assistant pop-up. The system shows it on power-button long press or the assist
 * gesture, and [TriggerActivity] shows it for headset buttons. Showing it starts a turn; the
 * turn itself lives in [com.hisbaan.orbit.assistant.Assistant], so it carries on if the
 * system hides the overlay (e.g. the screen turns off). It stays up after the turn until the
 * user closes it (tap outside, back), which also stops a turn in progress.
 */
class OrbitSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val app get() = context.applicationContext as OrbitApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // Stays up after the turn so the reply can be read; closes itself only to get out of
        // the way of an app an after-turn action opens.
        scope.launch { app.assistant.dismissRequests.collect { hide() } }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@OrbitSession)
        setViewTreeSavedStateRegistryOwner(this@OrbitSession)
        setViewTreeViewModelStoreOwner(this@OrbitSession)
        setContent {
            val state by app.assistant.state.collectAsState()
            OrbitTheme {
                AssistantOverlay(
                    state,
                    OverlayActions(
                        dismiss = ::close,
                        talk = { app.assistant.trigger("overlay", null) },
                        stop = app.assistant::cancel,
                        openApp = ::openApp,
                    ),
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val source = args?.getString(ARG_SOURCE) ?: if (showFlags and SHOW_SOURCE_ASSIST_GESTURE != 0) "assist gesture" else "session"
        val device = args?.let { BundleCompat.getParcelable(it, ARG_DEVICE, BluetoothDevice::class.java) }
        EventLog.log("assist", "Overlay shown: source=$source flags=$showFlags")
        app.assistant.trigger(source, device)
    }

    override fun onHide() {
        EventLog.log("assist", "Overlay hidden (turn ${if (app.assistant.state.value.phase == Phase.IDLE) "idle" else "continues"})")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onBackPressed() = close()

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun close() {
        app.assistant.cancel()
        hide()
    }

    private fun openApp() {
        hide()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val ARG_SOURCE = "orbit.source"
        private const val ARG_DEVICE = "orbit.device"

        fun args(source: String, device: BluetoothDevice?) = Bundle().apply {
            putString(ARG_SOURCE, source)
            device?.let { putParcelable(ARG_DEVICE, it) }
        }
    }
}
