package com.hisbaan.orbit.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Orbit's accessibility service: presses buttons in another app's UI for actions that app has
 * no API for (saving a reminder in Google Tasks, [TasksSheet]; Google Maps' navigation screen,
 * [MapsNav]), and screenshots that app's window to read what it draws without views (map
 * labels). Idle otherwise: it ignores
 * accessibility events, and reads only the windows of the one app a caller names, while that
 * call runs. (Renaming it would revoke the user's grant.)
 */
class ScreenControlService : AccessibilityService() {
    override fun onServiceConnected() {
        connected = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** The visible view `[pkg]:id/[id]` in [pkg]'s windows, waiting up to [timeoutMs] for it to appear. */
    suspend fun awaitView(pkg: String, id: String, timeoutMs: Long): AccessibilityNodeInfo? = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var view = findView(pkg, id)
        while (view == null && SystemClock.uptimeMillis() < deadline) {
            delay(POLL_MS)
            view = findView(pkg, id)
        }
        view
    }

    /** True once `[pkg]:id/[id]` is off screen, within [timeoutMs]. */
    suspend fun awaitGone(pkg: String, id: String, timeoutMs: Long): Boolean = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (findView(pkg, id) != null) {
            if (SystemClock.uptimeMillis() >= deadline) return@withContext false
            delay(POLL_MS)
        }
        true
    }

    fun findView(pkg: String, id: String): AccessibilityNodeInfo? =
        roots(pkg).firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
        }

    /**
     * The first node in [pkg]'s windows that [matches], depth first. Off-screen nodes (rows a
     * list has laid out but scrolled away) count only with [visibleOnly] false.
     */
    fun findNode(pkg: String, visibleOnly: Boolean = true, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((!visibleOnly || node.isVisibleToUser) && matches(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> search(child)?.let { return it } }
            return null
        }
        return roots(pkg).firstNotNullOfOrNull(::search)
    }

    /** Every node in [pkg]'s windows that [matches], in tree order. */
    fun findNodes(pkg: String, visibleOnly: Boolean = true, matches: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun search(node: AccessibilityNodeInfo) {
            if ((!visibleOnly || node.isVisibleToUser) && matches(node)) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::search)
        }
        roots(pkg).forEach(::search)
        return found
    }

    /** [findNode], waiting up to [timeoutMs] for it to appear. */
    suspend fun awaitNode(
        pkg: String,
        timeoutMs: Long,
        visibleOnly: Boolean = true,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var node = findNode(pkg, visibleOnly, matches)
        while (node == null && SystemClock.uptimeMillis() < deadline) {
            delay(POLL_MS)
            node = findNode(pkg, visibleOnly, matches)
        }
        node
    }

    /** True once no node [matches] (on screen, or anywhere with [visibleOnly] false), within [timeoutMs]. */
    suspend fun awaitNoNode(
        pkg: String,
        timeoutMs: Long,
        visibleOnly: Boolean = true,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (findNode(pkg, visibleOnly, matches) != null) {
                if (SystemClock.uptimeMillis() >= deadline) return@withContext false
                delay(POLL_MS)
            }
            true
        }

    fun click(node: AccessibilityNodeInfo): Boolean = node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    /** Clicks [node], or the nearest ancestor that takes clicks when it doesn't (a label inside a row). */
    fun clickRow(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target != null && click(target)
    }

    /**
     * The node that [matches], scrolled on screen: a list only has views for the rows near the
     * screen, so it scrolls the list holding the node (or else the largest list on screen)
     * toward it, or down and then back up while it isn't there, until it's visible. Null if
     * scrolling runs out first.
     */
    suspend fun scrollTo(pkg: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        withContext(Dispatchers.Main) {
            var down = true
            repeat(MAX_SCROLLS) {
                val node = findNode(pkg, visibleOnly = false, matches)
                if (node != null) {
                    if (node.isVisibleToUser) return@withContext node
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    node.refresh()
                    if (node.isVisibleToUser) return@withContext node
                }
                var list = node?.parent
                while (list != null && !list.isScrollable) list = list.parent
                list = list ?: findNodes(pkg) { it.isScrollable }.maxByOrNull { n ->
                    Rect().also(n::getBoundsInScreen).let { it.width() * it.height() }
                } ?: return@withContext null
                if (node != null) {
                    down = Rect().also(node::getBoundsInScreen).top >= Rect().also(list::getBoundsInScreen).top
                }
                val scrolled = list.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (!scrolled) {
                    // At the end without finding it: it's further up, if anywhere.
                    if (!down || node != null) return@withContext null
                    down = false
                }
                awaitStill(list)
            }
            null
        }

    /** Waits for [list] to stop scrolling (its rows in the same place three times running), up to a second and a half. */
    suspend fun awaitStill(list: AccessibilityNodeInfo) {
        fun rows(): List<Rect> {
            list.refresh()
            return (0 until list.childCount).mapNotNull { i -> list.getChild(i)?.let { Rect().also(it::getBoundsInScreen) } }
        }
        // The scroll may not have started yet when the action returns.
        delay(POLL_MS)
        var last = rows()
        var still = 0
        val deadline = SystemClock.uptimeMillis() + SCROLL_SETTLE_MAX_MS
        while (still < 2 && SystemClock.uptimeMillis() < deadline) {
            delay(POLL_MS)
            val now = rows()
            still = if (now == last) still + 1 else 0
            last = now
        }
    }

    /**
     * Taps the screen at ([x], [y]), for things drawn without views (a map's labels). It lands on
     * whatever window is on top there, so callers make sure nothing (Orbit's overlay) covers it.
     */
    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_MS)
        suspendCancellableCoroutine { done ->
            val sent = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = done.resume(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = done.resume(false)
                },
                null,
            )
            if (!sent) done.resume(false)
        }
    }

    /**
     * [pkg]'s top app window as a bitmap, and where that window sits on screen. From Android 14
     * the window alone, so Orbit's overlay isn't in it; before that the whole display.
     */
    suspend fun screenshot(pkg: String): Pair<Bitmap, Rect>? = withContext(Dispatchers.Main) {
        val window = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg }
            .maxByOrNull { it.layer } ?: return@withContext null
        val bounds = Rect().also(window::getBoundsInScreen)
        suspendCancellableCoroutine { done ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    // Hardware bitmaps can't be read pixel by pixel, which text recognition does.
                    val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    screenshot.hardwareBuffer.close()
                    done.resume(bitmap?.let { it to bounds })
                }

                override fun onFailure(errorCode: Int) {
                    EventLog.log("screen", "Screenshot of $pkg failed: error $errorCode")
                    done.resume(null)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(window.id, mainExecutor, callback)
            } else {
                bounds.offsetTo(0, 0)
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }
    }

    /**
     * Runs [block], which touches the app under Orbit's overlay ([tap]), with the overlay moved
     * out of the way, then brought back if it was up.
     */
    suspend fun <T> clearOfOverlay(block: suspend () -> T): T {
        val aside = besideOverlay ?: return block()
        var result: Any? = null
        aside {
            if (!awaitNoWindows(packageName, OVERLAY_GONE_MS)) EventLog.log("screen", "Orbit's overlay is still up")
            result = block()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** True once none of [pkg]'s windows are on screen (Orbit's overlay sliding away), within [timeoutMs]. */
    suspend fun awaitNoWindows(pkg: String, timeoutMs: Long): Boolean = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (windows.any { it.root?.packageName == pkg }) {
            if (SystemClock.uptimeMillis() >= deadline) return@withContext false
            delay(POLL_MS)
        }
        true
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * What's on screen, for the log when something isn't where it should be: every window, and
     * the labelled nodes in [pkg]'s, marked "(hidden)" when off screen.
     */
    fun outline(pkg: String, limit: Int = OUTLINE_NODES): String {
        val bounds = Rect()
        val shown = windows.joinToString { w ->
            "${w.type}/${w.layer}:${w.root?.packageName}${if (w.isInPictureInPictureMode) "(pip)" else ""}"
        }
        val nodes = findNodes(pkg, visibleOnly = false) { n ->
            !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || n.viewIdResourceName != null
        }.take(limit).joinToString(" | ") { n ->
            n.getBoundsInScreen(bounds)
            val label = n.text ?: n.contentDescription ?: ""
            "$label#${n.viewIdResourceName?.substringAfter(":id/").orEmpty()}${bounds.toShortString()}${if (n.isVisibleToUser) "" else "(hidden)"}"
        }
        return "windows [$shown]; $pkg: $nodes"
    }

    /**
     * Closes the keyboard if it's up (it hides the bottom of the screen), with Back: while the
     * keyboard shows, Back only closes it.
     */
    suspend fun hideKeyboard() = withContext(Dispatchers.Main) {
        fun shown() = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!shown()) return@withContext
        back()
        val deadline = SystemClock.uptimeMillis() + KEYBOARD_TIMEOUT_MS
        while (shown() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
    }

    /**
     * The package of the topmost app window (not the keyboard, system bars, Orbit's overlay or
     * a picture-in-picture window, e.g. Maps navigating while the user is in another app).
     */
    fun topApp(): String? = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        .maxByOrNull { it.layer }
        ?.root?.packageName?.toString()

    /**
     * [pkg]'s windows only, so a matching id in some other app's window is never touched. Read
     * fresh: the system keeps node caches current through the events a service takes, and this
     * one takes almost none, so cached nodes go stale as soon as a list scrolls and recycles its
     * rows (a click then lands on whatever row now holds the view).
     */
    private fun roots(pkg: String): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
        return (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName == pkg }
            .distinct()
    }

    companion object {
        /** Set while the user has the service turned on (and the phone has been unlocked since boot). */
        @Volatile
        var connected: ScreenControlService? = null
            private set

        /**
         * The service, waiting up to [timeoutMs] for the system to bind it if it's turned on: a
         * hail can start Orbit's process, and the system binds the service only a moment later.
         */
        suspend fun awaitConnected(context: Context, timeoutMs: Long = CONNECT_TIMEOUT_MS): ScreenControlService? {
            connected?.let { return it }
            if (!isEnabled(context)) return null
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                delay(POLL_MS)
                connected?.let { return it }
            }
            return null
        }

        /** Turned on in accessibility settings, whether or not the system has bound it yet. */
        fun isEnabled(context: Context): Boolean {
            val self = ComponentName(context, ScreenControlService::class.java)
            return context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }

        /** Set by the app: moves Orbit's overlay aside while the block runs, then brings it back. See [clearOfOverlay]. */
        @Volatile
        var besideOverlay: (suspend (suspend () -> Unit) -> Unit)? = null

        private const val POLL_MS = 150L
        private const val OVERLAY_GONE_MS = 3_000L
        private const val KEYBOARD_TIMEOUT_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val OUTLINE_NODES = 60
        private const val TAP_MS = 50L
        private const val MAX_SCROLLS = 16
        private const val SCROLL_SETTLE_MAX_MS = 1_500L
    }
}
