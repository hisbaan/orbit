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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Turn-by-turn navigation in Google Maps (a `dir_action=navigate` Maps URL), falling back to any
 * map app (`geo:`). Roads to avoid need Maps' `google.navigation:` intent instead, which Maps
 * URLs have no parameter for. Over a trip under way, says yes to Maps' "Exit navigation?"
 * ([MapsNav.confirmNewTrip]). After turn: Maps voice guidance takes audio focus.
 */
class NavigationTool(private val context: Context) : Tool {
    override val confirms = true

    // Maps URL travel modes. Without one, Maps uses the user's own preferred mode.
    private val modes = listOf("driving", "bicycling", "walking")

    // google.navigation's letters for the same, and for what to avoid.
    private val modeLetters = mapOf("driving" to "d", "bicycling" to "b", "walking" to "w")
    private val avoidLetters = mapOf("tolls" to "t", "highways" to "h", "ferries" to "f")

    override val spec = ToolSpec(
        name = "navigate",
        description = "Start turn-by-turn navigation to a destination, replacing any trip under way. Starts as you reply.",
        parameters = objectSchema(
            listOf("destination"),
            "destination" to stringProperty("Address, place name or search like 'nearest gas station'"),
            "mode" to stringProperty(
                "Travel mode, only if the user names one; leave out to use their Maps default",
                modes,
            ),
            "avoid" to buildJsonObject {
                put("type", "array")
                put("description", "Roads to avoid, only if the user asks")
                put("items", stringProperty("Road kind", avoidLetters.keys.toList()))
            },
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val destination = args.requireString("destination")
        val mode = args.string("mode")?.takeIf { it in modes }
        val avoid = (args["avoid"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(avoidLetters::get) }
        val url = if (avoid.isEmpty()) {
            val travel = mode?.let { "&travelmode=$it" }.orEmpty()
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&dir_action=navigate$travel"
        } else {
            val travel = mode?.let { "&mode=${modeLetters.getValue(it)}" }.orEmpty()
            "google.navigation:q=${Uri.encode(destination)}&avoid=${avoid.distinct().joinToString("")}$travel"
        }
        val maps = Intent(Intent.ACTION_VIEW, url.toUri())
            .setPackage(MapsNav.PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val generic = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(destination)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val replacing = MapsNav.isNavigating()
        return ToolOutcome(
            result = "Queued: navigation to '$destination' starts as you reply.",
            done = true,
            afterTurn = AfterTurnAction("navigate to '$destination'", needsUnlock = true, duringReply = true) {
                try {
                    context.startActivity(maps)
                    if (replacing) ScreenControlService.connected?.let { MapsNav.confirmNewTrip(it) }
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(generic)
                }
            },
        )
    }
}
