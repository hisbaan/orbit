package com.hisbaan.orbit.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.BundleCompat
import com.hisbaan.orbit.diagnostics.EventLog

/**
 * Orbit's notification listener. Granting it notification access unlocks other apps' media
 * sessions ([MediaSessions]) and lets [NotificationsTool] read the active notifications.
 * (The name predates the second use; renaming it would revoke the user's grant.)
 */
class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    companion object {
        /** The bound listener, while the system has it connected. */
        @Volatile
        var connected: MediaAccessService? = null
            private set
    }
}

/**
 * Other apps' media sessions, for direct control (play from search, shuffle, ...) without
 * opening their UI. Needs notification access; without it everything here returns nothing.
 */
class MediaSessions(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)

    val listenerComponent = ComponentName(appContext, MediaAccessService::class.java)

    val hasAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    /** Active sessions, most relevant first (the system orders by priority and recency). */
    fun controllers(): List<MediaController> = try {
        manager.getActiveSessions(listenerComponent)
    } catch (_: SecurityException) {
        emptyList()
    }

    /**
     * The session commands should go to: [preferredPackage]'s if it has one, otherwise
     * whatever is playing, otherwise the most recent. While Orbit holds focus nothing is
     * "playing", which is why recency is the fallback.
     */
    fun target(preferredPackage: String? = null): MediaController? {
        val all = controllers()
        return preferredPackage?.let { pkg -> all.firstOrNull { it.packageName == pkg } }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    }

    /**
     * "YouTube on Living Room TV (cast)" or "Spotify on this phone". The framework doesn't name
     * cast devices, so the name comes from the session's media notification when one exists.
     */
    fun describeWhere(controller: MediaController): String {
        val app = appLabel(appContext, controller.packageName)
        if (!controller.isRemote) return "$app on this phone"
        val device = castDevice(controller)
        return if (device != null) "$app on $device (cast)" else "$app on a cast device"
    }

    /** The session for [query]: an app name, a cast device name, or "cast"/"TV" for any cast session. */
    fun find(query: String): MediaController? {
        val all = controllers()
        val q = query.trim()
        return all.firstOrNull { appLabel(appContext, it.packageName).equals(q, ignoreCase = true) }
            ?: all.firstOrNull { describeWhere(it).contains(q, ignoreCase = true) }
            ?: all.firstOrNull { it.isRemote && CAST_WORDS.any { w -> q.contains(w, ignoreCase = true) } }
    }

    private fun castDevice(controller: MediaController): String? {
        val notification = MediaAccessService.connected?.let { listener ->
            runCatching { listener.activeNotifications.orEmpty() }.getOrDefault(emptyArray())
                .firstOrNull { sbn ->
                    BundleCompat.getParcelable(sbn.notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java) ==
                        controller.sessionToken
                }
        } ?: return null
        val extras = notification.notification.extras
        // Cast notifications put the device in the sub text ("Now playing on Home group").
        return (extras.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            ?.toString()?.replace(DEVICE_PREFIX, "")?.trim()?.takeIf { it.isNotBlank() }
    }

    fun log(message: String) = EventLog.log("media", message)

    private companion object {
        val CAST_WORDS = listOf("cast", "tv", "chromecast", "speaker", "nest", "television")
        val DEVICE_PREFIX = Regex("^(now playing on|playing on|casting to)\\s+", RegexOption.IGNORE_CASE)
    }
}

fun MediaController.supports(action: Long): Boolean = (playbackState?.actions ?: 0L) and action != 0L

/** Playing on another device (Cast, a remote speaker) rather than through the phone's audio. */
val MediaController.isRemote: Boolean
    get() = playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE

/**
 * The framework session API has no shuffle/repeat. AndroidX/Media3 sessions accept these
 * commands (it's what MediaControllerCompat sends), and most music apps use those.
 * Modes are PlaybackStateCompat's SHUFFLE_MODE_* / REPEAT_MODE_* values.
 */
object CompatSessionCommands {
    private const val SET_SHUFFLE_MODE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
    private const val ARGUMENT_SHUFFLE_MODE = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
    private const val SET_REPEAT_MODE = "android.support.v4.media.session.action.SET_REPEAT_MODE"
    private const val ARGUMENT_REPEAT_MODE = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"

    fun setShuffle(controller: MediaController, mode: Int) =
        controller.transportControls.sendCustomAction(SET_SHUFFLE_MODE, Bundle().apply { putInt(ARGUMENT_SHUFFLE_MODE, mode) })

    fun setRepeat(controller: MediaController, mode: Int) =
        controller.transportControls.sendCustomAction(SET_REPEAT_MODE, Bundle().apply { putInt(ARGUMENT_REPEAT_MODE, mode) })
}

const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
