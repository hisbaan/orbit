package com.hisbaan.orbit.tools

import android.content.Context
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.agent.booleanProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** A YouTube Music playlist the user saved in Orbit's settings under a spoken name. */
data class SavedPlaylist(val name: String, val playlistId: String) {
    companion object {
        private val LIST_PARAM = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        private val BARE_ID = Regex("""^[A-Za-z0-9_-]{10,}$""")

        /** Accepts a share link (`music.youtube.com/playlist?list=...`) or a bare playlist id. */
        fun parsePlaylistId(input: String): String? {
            val trimmed = input.trim()
            return LIST_PARAM.find(trimmed)?.groupValues?.get(1) ?: trimmed.takeIf { BARE_ID.matches(it) }
        }
    }
}

/**
 * Plays one of the user's own YouTube Music playlists. These are usually private, so search
 * can't find them; the user saves them in settings instead. The tool's description lists the
 * saved names (rebuilt per request), so the model can map "my music playlist" to "Music".
 * After turn: opens a `watch?list=` link, which YouTube Music plays.
 */
class PlaySavedPlaylistTool(
    private val context: Context,
    private val sessions: MediaSessions,
    private val playlists: () -> List<SavedPlaylist>,
) : Tool {
    override val confirms = true

    override val spec: ToolSpec
        get() {
            val names = playlists().map { it.name }
            return ToolSpec(
                name = "play_saved_playlist",
                description = "Play one of the user's own YouTube Music playlists. Use this, not play_music, " +
                    "whenever the user asks for one of their playlists by name. " +
                    (if (names.isEmpty()) "The user hasn't saved any yet." else "Saved playlists: ${names.joinToString()}.") +
                    " Starts after you finish speaking.",
                parameters = objectSchema(
                    listOf("name"),
                    "name" to stringProperty("Which saved playlist", names.takeIf { it.isNotEmpty() }),
                    "shuffle" to booleanProperty("Shuffle it (only if the user asked)"),
                ),
            )
        }

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val saved = playlists()
        if (saved.isEmpty()) {
            return ToolOutcome("No playlists are saved. Tell the user they can add them in Orbit's settings.")
        }
        val name = args.requireString("name")
        val playlist = saved.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return ToolOutcome("No saved playlist called '$name'. Saved: ${saved.joinToString { it.name }}.")
        val shuffle = args.boolean("shuffle") == true
        return ToolOutcome(
            "Queued: the '${playlist.name}' playlist${if (shuffle) " on shuffle" else ""} will start after you finish speaking.",
            done = true,
            afterTurn = AfterTurnAction("play the '${playlist.name}' playlist", needsUnlock = true) {
                openInYouTubeMusic(context, "https://music.youtube.com/watch?list=${playlist.playlistId}")
                if (shuffle) {
                    // The session reflects the new queue a moment after the link opens.
                    delay(SHUFFLE_DELAY_MS)
                    sessions.target(YOUTUBE_MUSIC)?.takeIf { it.packageName == YOUTUBE_MUSIC }?.let {
                        CompatSessionCommands.setShuffle(it, 1)
                        sessions.log("Shuffle on for '${playlist.name}'")
                    } ?: sessions.log("Couldn't shuffle '${playlist.name}': no YouTube Music session")
                }
            },
        )
    }

    private companion object {
        const val SHUFFLE_DELAY_MS = 2_500L
    }
}
