package com.hisbaan.orbit.assist

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
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
import com.hisbaan.orbit.tools.ScreenContext
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
    private var isWindowShown = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Drives the card's slide in and out; replaced when the window hides so the next show animates. */
    private var cardVisibility by mutableStateOf(MutableTransitionState(false))

    override fun onCreate() {
        super.onCreate()
        window.window?.let {
            // The card animates itself; the window shouldn't as well. Insets (IME, nav bar) go to Compose
            // (see stopPanning).
            it.setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(it, false)
        }
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // Stays up after the turn so the reply can be read; closes itself only to get out of
        // the way of an app an after-turn action opens.
        scope.launch { app.assistant.dismissRequests.collect { animateOut() } }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@OrbitSession)
        setViewTreeSavedStateRegistryOwner(this@OrbitSession)
        setViewTreeViewModelStoreOwner(this@OrbitSession)
        setContent {
            val state by app.assistant.state.collectAsState()
            val visible = cardVisibility
            // Hide the window once the card has finished sliding out.
            LaunchedEffect(visible, visible.isIdle, visible.currentState) {
                if (visible.isIdle && !visible.currentState && !visible.targetState && isWindowShown) {
                    EventLog.log("assist", "Card slid out; hiding")
                    hide()
                }
            }
            OrbitTheme {
                AssistantOverlay(
                    state,
                    visible,
                    OverlayActions(
                        dismiss = ::close,
                        talk = { app.assistant.trigger("overlay", null) },
                        ask = app.assistant::ask,
                        stop = app.assistant::cancel,
                        typing = {
                            stopPanning()
                            app.assistant.stopListening()
                        },
                        openApp = ::openApp,
                        openLink = ::openLink,
                    ),
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        isWindowShown = true
        cardVisibility.targetState = true
        val source = args?.getString(ARG_SOURCE) ?: if (showFlags and SHOW_SOURCE_ASSIST_GESTURE != 0) "assist gesture" else "session"
        val device = args?.let { BundleCompat.getParcelable(it, ARG_DEVICE, BluetoothDevice::class.java) }
        EventLog.log("assist", "Overlay shown: source=$source flags=$showFlags")
        ScreenContext.begin(text = showFlags and SHOW_WITH_ASSIST != 0, screenshot = showFlags and SHOW_WITH_SCREENSHOT != 0)
        app.assistant.trigger(source, device)
    }

    override fun onHide() {
        EventLog.log("assist", "Overlay hidden (turn ${if (app.assistant.state.value.phase == Phase.IDLE) "idle" else "continues"})")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        isWindowShown = false
        cardVisibility = MutableTransitionState(false)
        // What was on screen is only for this overlay; don't keep it around.
        ScreenContext.clear()
        super.onHide()
    }

    /** The app behind the overlay, as text (one call per activity). Kept for read_screen. */
    override fun onHandleAssist(state: AssistState) {
        EventLog.log("screen", "Assist data ${state.index + 1}/${state.count}: ${state.assistStructure?.activityComponent?.packageName ?: "none"}")
        ScreenContext.onStructure(state.assistStructure, context.packageName)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        EventLog.log("screen", "Screenshot: ${screenshot?.let { "${it.width}x${it.height}" } ?: "none"}")
        ScreenContext.onScreenshot(screenshot)
    }

    override fun onBackPressed() = close()

    override fun onCloseSystemDialogs() {
        EventLog.log("assist", "System asked to close dialogs")
        super.onCloseSystemDialogs()
    }

    override fun onLockscreenShown() {
        EventLog.log("assist", "Lock screen shown")
        super.onLockscreenShown()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun close() {
        app.assistant.cancel()
        animateOut()
    }

    /**
     * Compose lifts the card by the IME insets itself, but the session window is in adjustPan,
     * so the system also scrolled the whole window up to the focused field and the card flew
     * up twice the keyboard's height. Called when the field gains focus (the window is
     * attached by then, and the keyboard isn't up yet). The session's dialog doesn't pass
     * attribute changes on to the window manager, so they're pushed directly.
     */
    private fun stopPanning() {
        val w = window.window ?: return
        if (!w.decorView.isAttachedToWindow) return
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        context.getSystemService(WindowManager::class.java).updateViewLayout(w.decorView, w.attributes)
    }

    /** Slides the card away; the window hides when it's gone. */
    private fun animateOut() {
        EventLog.log("assist", "Closing overlay")
        cardVisibility.targetState = false
    }

    private fun openApp() {
        hide()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens a card's link: `app:<package>` launches that app, anything else is a web URL. */
    private fun openLink(link: String) {
        val intent = if (link.startsWith("app:")) {
            context.packageManager.getLaunchIntentForPackage(link.removePrefix("app:")) ?: return
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        }
        EventLog.log("assist", "Opening card link $link")
        hide()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
