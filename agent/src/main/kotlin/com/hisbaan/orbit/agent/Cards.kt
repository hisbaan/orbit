package com.hisbaan.orbit.agent

import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Something the assistant shows on screen next to its spoken reply. */
sealed interface Card {
    /**
     * [sky] is one of [SKIES]; temperatures are in [unit]. [link] is what tapping the card
     * opens: a web URL, or `app:<package>` for an app.
     */
    data class Weather(
        val place: String,
        val temperature: Double?,
        val feelsLike: Double?,
        val condition: String,
        val sky: String,
        val high: Double?,
        val low: Double?,
        val unit: String,
        val hours: List<HourSlot>,
        val days: List<DaySlot>,
        val link: String? = null,
    ) : Card

    data class HourSlot(val label: String, val temperature: Double?, val sky: String, val precipitationChance: Int?)

    data class DaySlot(val label: String, val high: Double?, val low: Double?, val sky: String, val precipitationChance: Int?)

    /** A titled list of label/value rows: events, facts, results. */
    data class Info(val title: String, val subtitle: String?, val rows: List<Row>) : Card

    data class Row(val label: String, val value: String)

    companion object {
        val SKIES = listOf(
            "clear", "clear_night", "partly_cloudy", "partly_cloudy_night", "cloudy", "fog", "drizzle", "rain", "snow", "sleet",
            "thunderstorm", "wind",
        )
    }
}

/** Shows a titled list (calendar events, search results, a recipe's steps...). Also confirming. */
class ShowInfoCardTool : Tool {
    override val confirms = true

    override val spec = ToolSpec(
        name = "show_info_card",
        description = "Show a short titled list on screen when seeing it helps: calendar events, a few results, " +
            "steps, a comparison. Rows are label/value pairs. Your spoken answer goes in the confirmation; don't " +
            "read every row aloud.",
        parameters = objectSchema(
            listOf("title", "rows"),
            "title" to stringProperty("Card title, e.g. 'Tomorrow'"),
            "subtitle" to stringProperty("Optional second line"),
            "rows" to arrayProperty(
                "Up to 10 rows",
                objectSchema(
                    listOf("label", "value"),
                    "label" to stringProperty("Left side, e.g. a time: '14:00'"),
                    "value" to stringProperty("Right side, e.g. 'Dentist'"),
                ),
                maxItems = 10,
            ),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val rows = args.objects("rows").take(10).map { Card.Row(it.string("label").orEmpty(), it.string("value").orEmpty()) }
        val card = Card.Info(args.requireString("title"), args.string("subtitle"), rows)
        return ToolOutcome("Shown.", done = true, cards = listOf(card))
    }
}

fun arrayProperty(description: String, items: JsonObject, maxItems: Int? = null): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
    maxItems?.let { put("maxItems", it) }
}

private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
