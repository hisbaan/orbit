package com.hisbaan.orbit.tools

import android.content.Context
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.agent.booleanProperty
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject

/*
 * Tools for the trip Google Maps is navigating, through its navigation screen ([MapsNav]).
 * Offered to the model only while Maps navigates.
 */

private suspend fun screenControl(context: Context): ScreenControlService = ScreenControlService.awaitConnected(context)
    ?: throw IllegalStateException(
        if (ScreenControlService.isEnabled(context)) {
            "Orbit's accessibility service is on but Android hasn't started it; turning it off and on in the phone's accessibility settings fixes that"
        } else {
            "Orbit's accessibility service is off; the user can turn it on in Orbit's setup"
        },
    )

class SearchAlongRouteTool(private val context: Context) : Tool {
    override val available get() = MapsNav.isNavigating()

    override val spec = ToolSpec(
        name = "search_along_route",
        description = "Find places along the route Google Maps is navigating (gas, coffee, food, a chain by name), " +
            "with the minutes each adds to the trip. Say the best two or three and ask which to add, unless the user " +
            "already chose (\"the closest one\"); then add it with add_stop.",
        parameters = objectSchema(
            listOf("query"),
            "query" to stringProperty("What to look for, e.g. 'gas stations', 'coffee', 'Tim Hortons', 'EV charging'"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val query = args.requireString("query").trim()
        val found = MapsNav.search(context, screenControl(context), query)
        if (found.isEmpty()) return ToolOutcome("Maps showed no '$query' it could read along the route.")
        return ToolOutcome(
            "Along the route for '$query', by time added: ${found.take(MAX_RESULTS).joinToString("; ") { it.describe() }}. " +
                "Names are as Maps labels them on the map, sometimes cut short.",
        )
    }

    private companion object {
        const val MAX_RESULTS = 8
    }
}

class AddStopTool(private val context: Context) : Tool {
    override val available get() = MapsNav.isNavigating()
    override val confirms = true

    override val spec = ToolSpec(
        name = "add_stop",
        description = "Add a place search_along_route found as a stop on the way, keeping the destination.",
        parameters = objectSchema(
            listOf("query", "name"),
            "query" to stringProperty("The search_along_route query it was found with"),
            "name" to stringProperty("Its name as search_along_route gave it"),
            "added_minutes" to integerProperty("Its added minutes, which tell apart places with the same name", minimum = 0),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val query = args.requireString("query").trim()
        val name = args.requireString("name").trim()
        val stop = MapsNav.addStop(context, screenControl(context), query, name, args.int("added_minutes"))
        return ToolOutcome("Added ${stop.describe()} as a stop.", done = true)
    }
}

class ReportRoadIssueTool(private val context: Context) : Tool {
    override val available get() = MapsNav.isNavigating()
    override val confirms = true

    private val kinds = (MapsNav.REPORTS + MapsNav.MORE_REPORTS).associateBy { it.lowercase().replace(' ', '_') }

    override val spec = ToolSpec(
        name = "report_road_issue",
        description = "Report something on the road to Google Maps, at the car's current spot, so other drivers are " +
            "warned. Only for what the user says they see.",
        parameters = objectSchema(listOf("kind"), "kind" to stringProperty("What it is", kinds.keys.toList())),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val kind = args.requireString("kind")
        val label = kinds[kind] ?: return ToolOutcome("Error: '$kind' isn't one of ${kinds.keys.joinToString()}")
        MapsNav.report(context, screenControl(context), label)
        return ToolOutcome("Reported '$label' to Maps.", done = true)
    }
}

class RouteOptionsTool(private val context: Context) : Tool {
    override val available get() = MapsNav.isNavigating()
    override val confirms = true

    private val options = listOf("avoid_tolls", "avoid_highways", "avoid_ferries").zip(MapsNav.AVOID)

    override val spec = ToolSpec(
        name = "route_options",
        description = "Change what Google Maps' route avoids for the trip under way (Maps re-routes); they stay set " +
            "for later trips. Give only what the user wants changed; give none to read them.",
        parameters = objectSchema(
            emptyList(),
            "avoid_tolls" to booleanProperty("Avoid toll roads"),
            "avoid_highways" to booleanProperty("Avoid highways"),
            "avoid_ferries" to booleanProperty("Avoid ferries"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val wanted = options.mapNotNull { (arg, label) -> args.boolean(arg)?.let { label to it } }.toMap()
        val now = MapsNav.routeOptions(context, screenControl(context), wanted)
        val avoided = now.filterValues { it }.keys.joinToString { it.removePrefix("Avoid ").lowercase() }
        return ToolOutcome(
            if (avoided.isEmpty()) "Maps' route now avoids nothing (tolls, highways and ferries all allowed)." else "Maps' route now avoids $avoided only.",
            done = wanted.isNotEmpty(),
        )
    }
}

class NavigationStatusTool(private val context: Context) : Tool {
    override val available get() = MapsNav.isNavigating()

    // Where the user is going.
    override val needsUnlock = true

    override val spec = ToolSpec(
        name = "navigation_status",
        description = "The trip Google Maps is navigating: stops and destination, time and distance left, arrival time, next turn.",
        parameters = objectSchema(),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val status = MapsNav.status(context, screenControl(context))
        return ToolOutcome(
            buildString {
                when (status.stops.size) {
                    0 -> append("Destination: unknown (Maps' directions list didn't show it).")
                    1 -> append("Destination: ${status.stops.single()}.")
                    else -> append("Stops: ${status.stops.dropLast(1).joinToString()}. Destination: ${status.stops.last()}.")
                }
                // With stops, Maps counts to the next one.
                status.timeLeft?.let { append(if (status.stops.size > 1) " To the next stop: $it" else " Time left: $it") }
                status.distanceAndArrival?.let { append(" (distance remaining $it)") }
                append(".")
                status.nextStep?.let { append(" Next: $it.") }
            },
        )
    }
}
