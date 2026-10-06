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
 * Turn-by-turn navigation in Google Maps (`google.navigation:`), falling back to any map app
 * (`geo:`). After turn: Maps voice guidance takes audio focus.
 */
class NavigationTool(private val context: Context) : Tool {
    // Google Maps navigation intent modes. Two-wheeler is only offered in some countries.
    private val modes = mapOf("driving" to "d", "two_wheeler" to "l", "bicycling" to "b", "walking" to "w")

    override val spec = ToolSpec(
        name = "navigate",
        description = "Start turn-by-turn navigation to a destination. Starts after you finish speaking.",
        parameters = objectSchema(
            listOf("destination"),
            "destination" to stringProperty("Address, place name or search like 'nearest gas station'"),
            "mode" to stringProperty("Travel mode; default driving", modes.keys.toList()),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val destination = args.requireString("destination")
        val mode = modes[args.string("mode")] ?: "d"
        val maps = Intent(Intent.ACTION_VIEW, "google.navigation:q=${Uri.encode(destination)}&mode=$mode".toUri())
            .setPackage(GOOGLE_MAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val generic = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(destination)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ToolOutcome(
            result = "Queued: navigation to '$destination' will start after you finish speaking.",
            afterTurn = AfterTurnAction("navigate to '$destination'", needsUnlock = true) {
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
