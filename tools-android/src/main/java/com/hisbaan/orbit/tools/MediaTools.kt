package com.hisbaan.orbit.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.VolumeProvider
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import androidx.core.net.toUri
import com.hisbaan.orbit.ytmusic.YouTubeMusicSearch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.roundToInt

private const val NO_ACCESS =
    "Error: Orbit doesn't have notification access, which it needs for this. Tell the user to enable it in Orbit."

/**
 * Controls whatever is playing (or last played). Uses the app's media session when Orbit has
 * notification access, which also allows shuffle, repeat and app-specific actions like "Like";
 * otherwise falls back to media key events (play/pause/skip only).
 *
 * Immediate: a pause sent while Orbit holds audio focus sticks, so the player stays paused
 * when focus is handed back. A play is applied when focus returns.
 */
class MediaControlTool(
    context: Context,
    private val sessions: MediaSessions,
    private val musicPackage: suspend () -> String?,
) : Tool {
    override val confirms = true

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val keys = mapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
    )
    // Values of PlaybackStateCompat's SHUFFLE_MODE_* / REPEAT_MODE_*.
    private val shuffle = mapOf("shuffle_on" to 1, "shuffle_off" to 0)
    private val repeat = mapOf("repeat_off" to 0, "repeat_one" to 1, "repeat_all" to 2)

    private val volume = listOf("volume_up", "volume_down", "set_volume")

    override val spec = ToolSpec(
        name = "media_control",
        description = "Control the music or media that is playing (or was last playing), on the phone or on a cast " +
            "device (TV, speaker). 'play' resumes it after you finish speaking. 'custom' runs one of the player's own " +
            "actions (e.g. 'Like'); call media_info first to see which exist. Volume actions change the cast device's " +
            "volume when the player is casting, otherwise the phone's media volume.",
        parameters = objectSchema(
            listOf("action"),
            "action" to stringProperty("What to do", (keys.keys + shuffle.keys + repeat.keys + volume + "custom").toList()),
            "player" to stringProperty("Which player, if not the main one: an app name or a cast device name as media_info lists it"),
            "custom_action" to stringProperty("For action 'custom': the action's name as listed by media_info"),
            "volume_percent" to integerProperty("For action 'set_volume': 0 to 100", minimum = 0, maximum = 100),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val action = args.requireString("action")
        val player = args.string("player")
        val controller = if (player != null) {
            sessions.find(player) ?: return ToolOutcome(
                "No player matches '$player'. Active: ${sessions.controllers().joinToString { sessions.describeWhere(it) }.ifEmpty { "none" }}",
            )
        } else {
            sessions.target(musicPackage())
        }
        if (action in volume) return changeVolume(action, args.int("volume_percent"), controller)
        if (controller == null) {
            val code = keys[action] ?: return ToolOutcome(if (sessions.hasAccess) "Nothing is playing." else NO_ACCESS)
            sendKey(code)
            return ToolOutcome(keyResult(action), done = true)
        }
        val app = sessions.describeWhere(controller)
        val controls = controller.transportControls
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
            in shuffle -> CompatSessionCommands.setShuffle(controller, shuffle.getValue(action))
            in repeat -> CompatSessionCommands.setRepeat(controller, repeat.getValue(action))
            "custom" -> {
                val name = args.requireString("custom_action")
                val custom = controller.playbackState?.customActions.orEmpty()
                val match = custom.firstOrNull { it.name.toString().equals(name, ignoreCase = true) }
                    ?: return ToolOutcome("Error: $app has no action '$name'. Available: ${custom.joinToString { it.name }.ifEmpty { "none" }}")
                controls.sendCustomAction(match, null)
            }
            else -> return ToolOutcome("Error: unknown action '$action'")
        }
        sessions.log("$action -> ${controller.packageName}")
        return ToolOutcome(
            if (action == "play" && !controller.isRemote) keyResult(action) else "Sent '$action' to $app.",
            done = true,
        )
    }

    /** A cast session's own volume when [controller] is casting, otherwise the phone's media volume. */
    private fun changeVolume(action: String, percent: Int?, controller: MediaController?): ToolOutcome {
        if (action == "set_volume" && percent == null) return ToolOutcome("Error: set_volume needs volume_percent.")
        if (controller != null && controller.isRemote) {
            val info = controller.playbackInfo
            if (info.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED) {
                return ToolOutcome("${sessions.describeWhere(controller)} doesn't allow volume changes from the phone.")
            }
            when (action) {
                "volume_up" -> controller.adjustVolume(AudioManager.ADJUST_RAISE, 0)
                "volume_down" -> controller.adjustVolume(AudioManager.ADJUST_LOWER, 0)
                else -> controller.setVolumeTo((info.maxVolume * percent!! / 100.0).roundToInt(), 0)
            }
            sessions.log("$action -> remote ${controller.packageName}")
            return ToolOutcome(
                "Changed the volume on ${sessions.describeWhere(controller)} (was ${info.currentVolume} of ${info.maxVolume}).",
                done = true,
            )
        }
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        when (action) {
            // Two steps, so one request is a change the user can hear.
            "volume_up" -> repeat(2) { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
            "volume_down" -> repeat(2) { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
            else -> audioManager.setStreamVolume(stream, (max * percent!! / 100.0).roundToInt(), 0)
        }
        val now = audioManager.getStreamVolume(stream)
        return ToolOutcome("Phone media volume is now ${now * 100 / max}%.", done = true)
    }

    private fun sendKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun keyResult(action: String) =
        if (action == "play") "Playback will resume when you finish speaking." else "Sent '$action' to the media player."

    private fun MediaController.appLabel(): String = appLabel(appContext, packageName)
}

/** What's playing, in which app, and which player-specific actions exist. */
class MediaInfoTool(
    private val context: Context,
    private val sessions: MediaSessions,
    private val musicPackage: suspend () -> String?,
) : Tool {
    override val spec = ToolSpec(
        name = "media_info",
        description = "Find out what music or media is playing, on the phone or cast to a TV or speaker: title, artist, " +
            "app, state, where it plays, and which player-specific actions media_control can run. Lists every active player.",
        parameters = objectSchema(),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        if (!sessions.hasAccess) return ToolOutcome(NO_ACCESS)
        val main = sessions.target(musicPackage()) ?: return ToolOutcome("Nothing is playing and no media app is active.")
        val others = sessions.controllers().filter { it.sessionToken != main.sessionToken }.take(3)
        val lines = listOf("Main player: ${describe(main, withActions = true)}") + others.map { "Other player: ${describe(it, withActions = false)}" }
        // Orbit holds audio focus during a turn, so a local "paused" usually means "paused for Orbit".
        return ToolOutcome(lines.joinToString("\n") + "\n(Players on this phone are paused while you are talking to the user.)")
    }

    private fun describe(controller: MediaController, withActions: Boolean): String {
        val meta = controller.metadata
        val state = controller.playbackState
        return listOfNotNull(
            sessions.describeWhere(controller),
            "state: ${stateName(state?.state)}",
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { "title: $it" },
            (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.let { "artist: $it" },
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { "album: $it" },
            controller.takeIf { it.isRemote }?.playbackInfo?.let { "volume: ${it.currentVolume} of ${it.maxVolume}" },
            state?.customActions?.takeIf { withActions && it.isNotEmpty() }?.let { actions -> "custom actions: ${actions.joinToString { it.name }}" },
        ).joinToString("; ")
    }

    private fun stateName(state: Int?) = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        null -> "unknown"
        else -> "state $state"
    }
}

/**
 * Starts playback of a search in the user's music app. Three paths:
 *
 * - **YouTube Music:** it only accepts `playFromSearch` from allowlisted (Google) apps, so Orbit
 *   searches its catalog itself ([YouTubeMusicSearch]) and opens a `watch` link, which plays.
 * - **Other apps with a media session:** `playFromSearch` on the session; starts directly and
 *   works with the phone locked.
 * - **Fallback** (no session, no notification access, or search failed): the
 *   `MEDIA_PLAY_FROM_SEARCH` intent, which opens the app.
 *
 * After turn: the player takes audio focus and must not start while Orbit's SCO link is up.
 */
class PlayMusicTool(
    private val context: Context,
    private val sessions: MediaSessions,
    private val youTubeMusic: YouTubeMusicSearch,
    /** Package of the preferred music app, or null for the system's choice. */
    private val musicPackage: suspend () -> String?,
) : Tool {
    override val confirms = true

    private val focusTypes = mapOf(
        "song" to "vnd.android.cursor.item/audio",
        "artist" to MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
        "album" to MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE,
        "playlist" to "vnd.android.cursor.item/playlist",
        "genre" to MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE,
    )

    override val spec = ToolSpec(
        name = "play_music",
        description = "Search for and play music, in the app the user named or else their music app. Only use this " +
            "when the user explicitly asks to play or listen to something. Starts after you finish speaking.",
        parameters = objectSchema(
            listOf("query"),
            "app" to stringProperty("The app to play in, only if the user named one, e.g. 'Spotify'"),
            "query" to stringProperty("What to play, as the user would type it into a music search, e.g. 'Bohemian Rhapsody Queen'"),
            "kind" to stringProperty("What the query names, if clear", listOf("any") + focusTypes.keys),
            "artist" to stringProperty("Artist name, if known"),
            "album" to stringProperty("Album name, if known"),
            "title" to stringProperty("Song title, if known"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val query = args.requireString("query")
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, focusTypes[args.string("kind")] ?: "vnd.android.cursor.item/*")
            args.string("artist")?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
            args.string("album")?.let { putString(MediaStore.EXTRA_MEDIA_ALBUM, it) }
            args.string("title")?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
        }
        // The app the user named, else their chosen one, else what's playing, else the system's default.
        val named = args.string("app")
        val pkg = if (named != null) {
            musicApps().let { apps -> matchApp(apps, named) ?: return ToolOutcome("No music app called '$named'. Installed: ${apps.joinToString { it.first }}.") }
        } else {
            musicPackage() ?: sessions.target()?.packageName ?: defaultMusicApp()
        }
        sessions.log("play_music '$query' -> ${pkg ?: "no app"}${named?.let { " (asked for $it)" } ?: ""}")
        if (pkg == YOUTUBE_MUSIC) {
            playOnYouTubeMusic(query, args.string("kind"), args.string("artist"))?.let { return it }
        }
        if (pkg == SPOTIFY) return spotifySearch(query, extras)
        val session = sessions.target(pkg)
            ?.takeIf { (pkg == null || it.packageName == pkg) && it.supports(PlaybackState.ACTION_PLAY_FROM_SEARCH) }
        val result = "Queued: '$query' will start playing after you finish speaking."

        if (session != null) {
            val app = session.packageName
            return ToolOutcome(
                result,
                AfterTurnAction("play '$query'", needsUnlock = false) {
                    val controller = sessions.target(app)?.takeIf { it.packageName == app } ?: session
                    sessions.log("playFromSearch('$query') -> $app")
                    controller.transportControls.playFromSearch(query, extras)
                },
                done = true,
            )
        }

        sessions.log("No session for ${pkg ?: "any app"} with play-from-search (access=${sessions.hasAccess}); using the intent")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtras(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ToolOutcome(
            result,
            AfterTurnAction("play '$query'", needsUnlock = true) {
                val targeted = pkg?.let { Intent(intent).setPackage(it) }
                try {
                    context.startActivity(targeted ?: intent)
                } catch (e: ActivityNotFoundException) {
                    if (targeted == null) throw e
                    context.startActivity(intent)
                }
            },
            done = true,
        )
    }

    /**
     * Spotify only searches when other apps ask it to play something: it ignores play-from-search
     * through its media session, refuses our media browser connection, and its Web API needs a
     * Premium developer account. Opening a track link would play, but finding the track needs that
     * API. So open its search and tell the model it's a search, not playback.
     */
    private fun spotifySearch(query: String, extras: Bundle): ToolOutcome {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, query)
            .putExtras(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ToolOutcome(
            "Spotify will open its search results for '$query' after you finish speaking, but it doesn't let other " +
                "apps start a song, so the user has to tap it. Say that briefly.",
            AfterTurnAction("search Spotify for '$query'", needsUnlock = true) { context.startActivity(intent) },
        )
    }

    /** Installed apps that take play-from-search requests, as (label, package). */
    private fun musicApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    /** The app handling play-from-search by default, or null if the user hasn't picked one. */
    private fun defaultMusicApp(): String? = context.packageManager
        .resolveActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName?.takeIf { it != "android" }

    /** Searches YouTube Music and queues the best match. Null means "use the generic path". */
    private suspend fun playOnYouTubeMusic(query: String, kind: String?, artist: String?): ToolOutcome? {
        val locale = Locale.getDefault()
        val results = try {
            youTubeMusic.search(query, locale.language.ifEmpty { "en" }, locale.country.ifEmpty { "US" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessions.log("YouTube Music search failed ($e); falling back to the intent")
            return null
        }
        val pick = YouTubeMusicSearch.pick(results, kind, artist)
            ?: return ToolOutcome("YouTube Music found nothing playable for '$query'.")
        sessions.log("YouTube Music search '$query' (kind=$kind, artist=$artist) -> ${pick.describe()}")
        return ToolOutcome(
            "Found ${pick.describe()}. It will start playing after you finish speaking.",
            AfterTurnAction("play ${pick.describe()}", needsUnlock = true) { openInYouTubeMusic(context, pick.playUrl!!) },
            done = true,
        )
    }

    companion object {
        private const val SPOTIFY = "com.spotify.music"

        /** The app [said] names: an exact label first ("YouTube Music" over "YouTube"), then a partial one. */
        fun matchApp(apps: List<Pair<String, String>>, said: String): String? {
            val wanted = said.trim()
            return apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.second
                ?: apps.filter { it.first.contains(wanted, ignoreCase = true) || wanted.contains(it.first, ignoreCase = true) }
                    .maxByOrNull { it.first.length }?.second
        }
    }
}

/** Opens a `music.youtube.com/watch` link, which YouTube Music starts playing. */
internal fun openInYouTubeMusic(context: Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(YOUTUBE_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) {
    packageName
}
