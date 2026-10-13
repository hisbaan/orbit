package com.hisbaan.orbit.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Turn-by-turn navigation in Google Maps (a `dir_action=navigate` Maps URL), falling back to any
 * map app (`geo:`). After turn: Maps voice guidance takes audio focus.
 */
class NavigationTool(private val context: Context) : Tool {
    override val confirms = true

    // Maps URL travel modes. Without one, Maps uses the user's own preferred mode.
    private val modes = listOf("driving", "bicycling", "walking")

    override val spec = ToolSpec(
        name = "navigate",
        description = "Start turn-by-turn navigation to a destination. Starts as you reply.",
        parameters = objectSchema(
            listOf("destination"),
            "destination" to stringProperty("Address, place name or search like 'nearest gas station'"),
            "mode" to stringProperty(
                "Travel mode, only if the user names one; leave out to use their Maps default",
                modes,
            ),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val destination = args.requireString("destination")
        val mode = args.string("mode")?.takeIf { it in modes }?.let { "&travelmode=$it" }.orEmpty()
        val url = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&dir_action=navigate$mode"
        val maps = Intent(Intent.ACTION_VIEW, url.toUri())
            .setPackage(GOOGLE_MAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val generic = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(destination)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ToolOutcome(
            result = "Queued: navigation to '$destination' starts as you reply.",
            done = true,
            afterTurn = AfterTurnAction("navigate to '$destination'", needsUnlock = true, duringReply = true) {
                try {
                    context.startActivity(maps)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(generic)
                }
            },
        )
    }

    private companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
    }
}
