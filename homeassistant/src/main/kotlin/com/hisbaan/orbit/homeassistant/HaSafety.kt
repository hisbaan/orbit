package com.hisbaan.orbit.homeassistant

/**
 * Which Home Assistant actions need the user's yes first: anything that could let someone in.
 * Unlocking, disarming, and opening a garage, gate or door, whichever way it's reached: the
 * domain's own service, the generic `homeassistant.*` services, or a button, script, scene,
 * automation or switch that does it. Those last ones say nothing about what they do, so they
 * are judged by name ("Garage door opener", `script.open_gate`), not counting lights.
 */
internal object HaSafety {
    /** Service data keys HA treats as targets on top of `entity_id`; Orbit sets the targets itself. */
    val TARGET_KEYS = setOf("entity_id", "device_id", "area_id", "floor_id", "label_id")

    /**
     * Whether calling [domain].[service] (both lowercase) on [entity] needs confirmation. [domain]
     * is the service's domain, which may be `homeassistant` rather than the entity's own.
     */
    fun needsConfirmation(domain: String, service: String, entity: HaEntity): Boolean {
        // homeassistant.turn_off closes a cover; the other generic services may open or unlock.
        val closing = service in CLOSING || (domain == "homeassistant" && service == "turn_off")
        return when (entity.domain) {
            "lock" -> service != "lock"
            "alarm_control_panel" -> !(service.startsWith("alarm_arm") || service == "alarm_trigger")
            "cover" -> !closing && (entity.attr("device_class") in ENTRYWAY_CLASSES || namesEntryway(entity.searchText()))
            in TRIGGERS -> namesEntryway(entity.searchText())
            else -> false
        }
    }

    /** Whether [text] (a name, an entity id, a request in words) is about a lock, garage, gate or door. */
    fun namesEntryway(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return words.any { it in ENTRYWAY_WORDS } && words.none { it in LIGHT_WORDS }
    }

    private fun HaEntity.searchText() = "$entityId $name"

    private val CLOSING = setOf("lock", "close_cover", "close_cover_tilt", "stop_cover", "stop_cover_tilt")
    private val ENTRYWAY_CLASSES = setOf("garage", "gate", "door")

    /** Domains whose entities do whatever they were set up to do, so only their names say what. */
    private val TRIGGERS = setOf("button", "input_button", "script", "scene", "automation", "switch", "input_boolean")

    private val ENTRYWAY_WORDS = setOf("garage", "gate", "gates", "door", "doors", "lock", "locks", "unlock", "disarm", "opener")

    /** "Garage light", "Front door lamp": named after a door, but only lights. */
    private val LIGHT_WORDS = setOf("light", "lights", "lamp", "lamps", "bulb", "bulbs", "led", "leds")
}
