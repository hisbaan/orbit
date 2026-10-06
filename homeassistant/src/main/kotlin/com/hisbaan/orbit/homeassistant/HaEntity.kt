package com.hisbaan.orbit.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

data class HaEntity(val entityId: String, val state: String, val attributes: JsonObject) {
    val domain: String get() = entityId.substringBefore('.')
    val name: String get() = attr("friendly_name") ?: entityId.substringAfter('.').replace('_', ' ')

    fun attr(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
    fun number(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull

    /** One line for the model: `light.kitchen "Kitchen light" [Kitchen]: on, brightness 70%`. */
    fun describe(area: String? = null): String {
        val details = buildList {
            when (domain) {
                "light" -> number("brightness")?.let { add("brightness ${(it / 255 * 100).roundToInt()}%") }
                "climate" -> {
                    number("current_temperature")?.let { add("currently ${it.clean()}°") }
                    number("temperature")?.let { add("target ${it.clean()}°") }
                    attr("hvac_action")?.let { add(it) }
                }
                "cover" -> number("current_position")?.let { add("position ${it.roundToInt()}%") }
                "fan" -> number("percentage")?.let { add("speed ${it.roundToInt()}%") }
                "media_player" -> {
                    attr("media_title")?.let { add("playing '$it'" + (attr("media_artist")?.let { a -> " by $a" } ?: "")) }
                    number("volume_level")?.let { add("volume ${(it * 100).roundToInt()}%") }
                }
                "sensor", "number", "input_number" -> attr("unit_of_measurement")?.let { return line(area, "${state.trim()} $it") }
            }
        }
        return line(area, (listOf(state) + details).joinToString(", "))
    }

    private fun line(area: String?, status: String) = "$entityId \"$name\"${area?.let { " [$it]" } ?: ""}: $status"

    companion object {
        fun parse(json: JsonObject): HaEntity? {
            val id = (json["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return null
            return HaEntity(id, (json["state"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (json["attributes"] as? JsonObject) ?: JsonObject(emptyMap()))
        }

        /** Domains people control by voice; the default listing when nothing narrower is asked. */
        val CONTROLLABLE = setOf(
            "light", "switch", "fan", "climate", "cover", "lock", "media_player", "scene", "script", "vacuum",
            "humidifier", "water_heater", "input_boolean", "button", "alarm_control_panel", "valve",
        )

        /**
         * Entities matching [query] (every word must appear in the id, name or area) and
         * [domain]. With neither, only [CONTROLLABLE] domains, so sensors don't swamp the list.
         */
        fun filter(entities: List<HaEntity>, areas: Map<String, String>, query: String?, domain: String?): List<HaEntity> {
            val words = query?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            return entities.filter { e ->
                (domain == null || e.domain == domain) &&
                    (domain != null || words.isNotEmpty() || e.domain in CONTROLLABLE) &&
                    words.all { w -> "${e.entityId} ${e.name} ${areas[e.entityId].orEmpty()}".lowercase().contains(w) }
            }
        }

        private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
    }
}
