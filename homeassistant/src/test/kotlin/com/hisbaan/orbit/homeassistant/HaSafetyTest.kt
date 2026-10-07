package com.hisbaan.orbit.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaSafetyTest {
    private fun entity(id: String, name: String, deviceClass: String? = null) = HaEntity(
        id,
        "off",
        JsonObject(listOfNotNull("friendly_name" to JsonPrimitive(name), deviceClass?.let { "device_class" to JsonPrimitive(it) }).toMap()),
    )

    private val frontDoor = entity("lock.front_door", "Front door")
    private val garage = entity("cover.garage", "Garage door", "garage")
    private val blinds = entity("cover.bedroom_blinds", "Bedroom blinds", "blind")
    private val alarm = entity("alarm_control_panel.home", "Home alarm")

    private fun asks(domain: String, service: String, e: HaEntity) = HaSafety.needsConfirmation(domain, service, e)

    @Test
    fun `locks ask for anything but locking`() {
        assertTrue(asks("lock", "unlock", frontDoor))
        assertTrue(asks("lock", "open", frontDoor))
        assertTrue(asks("homeassistant", "turn_on", frontDoor))
        assertFalse(asks("lock", "lock", frontDoor))
    }

    @Test
    fun `alarms ask for disarming, not arming`() {
        assertTrue(asks("alarm_control_panel", "alarm_disarm", alarm))
        assertTrue(asks("homeassistant", "toggle", alarm))
        assertFalse(asks("alarm_control_panel", "alarm_arm_away", alarm))
    }

    @Test
    fun `entryway covers ask for anything but closing, other covers never`() {
        assertTrue(asks("cover", "open_cover", garage))
        assertTrue(asks("cover", "toggle", garage))
        assertTrue(asks("cover", "set_cover_position", garage))
        assertTrue(asks("homeassistant", "toggle", garage))
        assertTrue(asks("homeassistant", "turn_on", garage))
        assertFalse(asks("cover", "close_cover", garage))
        assertFalse(asks("homeassistant", "turn_off", garage))
        assertFalse(asks("cover", "open_cover", blinds))
        // No device class: judged by name.
        assertTrue(asks("cover", "open_cover", entity("cover.side_gate", "Side gate")))
    }

    @Test
    fun `buttons, scripts and switches ask when named after an entryway, unless they're lights`() {
        assertTrue(asks("button", "press", entity("button.garage_opener", "Garage door opener")))
        assertTrue(asks("script", "turn_on", entity("script.open_gate", "Open gate")))
        assertTrue(asks("switch", "turn_on", entity("switch.garage_relay", "Garage relay")))
        assertTrue(asks("automation", "trigger", entity("automation.unlock_on_arrival", "Unlock on arrival")))
        assertFalse(asks("switch", "turn_on", entity("switch.garage_light", "Garage light")))
        assertFalse(asks("switch", "turn_on", entity("switch.porch", "Front door lamp")))
        assertFalse(asks("scene", "turn_on", entity("scene.movie_night", "Movie night")))
        // Whole words: a clock isn't a lock, a doorbell isn't a door.
        assertFalse(asks("switch", "turn_on", entity("switch.clock_display", "Clock display")))
        assertFalse(asks("button", "press", entity("button.doorbell_chime", "Doorbell chime")))
    }

    @Test
    fun `plain-words requests about entryways`() {
        assertTrue(HaSafety.namesEntryway("open the garage"))
        assertTrue(HaSafety.namesEntryway("unlock the front door"))
        assertFalse(HaSafety.namesEntryway("turn on the garage lights"))
        assertFalse(HaSafety.namesEntryway("set the living room to 21 degrees"))
    }
}
