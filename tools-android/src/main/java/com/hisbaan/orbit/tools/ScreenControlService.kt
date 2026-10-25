package com.hisbaan.orbit.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Orbit's accessibility service: presses buttons in another app's UI for actions that app has
 * no API for (saving a reminder in Google Tasks, [TasksSheet]). Idle otherwise: it ignores
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

    fun click(node: AccessibilityNodeInfo): Boolean = node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** The package of the topmost app window (not the keyboard, system bars or Orbit's overlay). */
    fun topApp(): String? = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .maxByOrNull { it.layer }
        ?.root?.packageName?.toString()

    /** [pkg]'s windows only, so a matching id in some other app's window is never touched. */
    private fun roots(pkg: String): List<AccessibilityNodeInfo> =
        (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName == pkg }
            .distinct()

    companion object {
        /** Set while the user has the service turned on (and the phone has been unlocked since boot). */
        @Volatile
        var connected: ScreenControlService? = null
            private set

        /** Turned on in accessibility settings, whether or not the system has bound it yet. */
        fun isEnabled(context: Context): Boolean {
            val self = ComponentName(context, ScreenControlService::class.java)
            return context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }

        private const val POLL_MS = 150L
    }
}
