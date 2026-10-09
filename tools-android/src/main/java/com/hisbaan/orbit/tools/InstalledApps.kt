package com.hisbaan.orbit.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale

/** The app's name as the launcher shows it ("YouTube Music"), or its package if it has none. */
internal fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) {
    packageName
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Apps that handle an intent, as (label, package), deduplicated by package. Loading every
 * app's label takes around a second ("open maps" spent 1.4 s resolving the app on the first
 * ride), so lists are cached until a package is installed, updated or removed, or the
 * language changes. The package manager reports changes by sequence number, so no broadcast
 * receiver is needed.
 */
internal object InstalledApps {
    private var sequence = 0
    private var locale: Locale? = null
    private val cache = mutableMapOf<String, List<Pair<String, String>>>()

    @Synchronized
    fun handling(context: Context, intent: Intent): List<Pair<String, String>> {
        val pm = context.packageManager
        val changed = pm.getChangedPackages(sequence)
        if (changed != null || locale != Locale.getDefault()) {
            changed?.let { sequence = it.sequenceNumber }
            locale = Locale.getDefault()
            cache.clear()
        }
        val key = "${intent.action} ${intent.categories.orEmpty().sorted()}"
        return cache.getOrPut(key) {
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
        }
    }
}
