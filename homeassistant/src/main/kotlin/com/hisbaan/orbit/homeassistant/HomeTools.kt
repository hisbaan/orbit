package com.hisbaan.orbit.homeassistant

import com.hisbaan.orbit.agent.PendingAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The Home Assistant tools. [connection] is null while HA isn't set up; the tools then hide
 * themselves from the model.
 */
class HomeTools(private val connection: () -> HomeAssistant?, private val language: () -> String) {
    val all: List<Tool> get() = listOf(callService, states, command)

    private abstract inner class HaTool : Tool {
        override val available: Boolean get() = connection() != null

        override suspend fun invoke(args: JsonObject): ToolOutcome {
            val ha = connection() ?: return ToolOutcome("Home Assistant isn't connected. Tell the user to connect it in Orbit's settings.")
            return reportingErrors { run(ha, args) }
        }

        abstract suspend fun run(ha: HomeAssistant, args: JsonObject): ToolOutcome

        /** Turns Home Assistant's errors into results the model can act on. */
        protected suspend fun reportingErrors(block: suspend () -> ToolOutcome): ToolOutcome = try {
            block()
        } catch (e: HaException) {
            ToolOutcome(
                if (e.unauthorized) "Error: Home Assistant rejected Orbit's sign-in. Tell the user to sign in again in Orbit's settings."
                else "Error: ${e.message}",
            )
        }
    }

    val command: Tool = object : HaTool() {
        override val spec = ToolSpec(
            name = "home_command",
            description = "Hand a request in plain words to Home Assistant's own assistant, for what home_call_service " +
                "can't express, e.g. the user's aliases or HA-specific phrasing. It only sees devices the user exposed " +
                "to Assist and can report success while skipping others, so use home_call_service for devices and rooms.",
            parameters = objectSchema(
                listOf("text"),
                "text" to stringProperty("The command in simple English, naming the device or room as the user did"),
            ),
        )

        override suspend fun run(ha: HomeAssistant, args: JsonObject): ToolOutcome {
            val text = args.requireString("text")
            val perform: suspend () -> ToolOutcome = { converse(ha, text) }
            // Assist does whatever the words say, so anything about locks or doors waits for a yes.
            if (!HaSafety.namesEntryway(text)) return perform()
            return ToolOutcome(
                "Not done yet: '$text' may unlock or open something, so it needs the user's confirmation. Ask them.",
                pending = PendingAction("ask Home Assistant to '$text'", needsUnlock = true) { reportingErrors(perform) },
            )
        }

        private suspend fun converse(ha: HomeAssistant, text: String): ToolOutcome {
            val result = ha.converse(text, language())
            return ToolOutcome(
                when (result.responseType) {
                    "error" -> "Home Assistant didn't do it (${result.errorCode ?: "error"}): ${result.speech}. " +
                        "Use home_call_service instead (by name, entity id or area)."
                    else -> "Home Assistant: ${result.speech.ifBlank { "done" }}"
                },
            )
        }
    }

    val states: Tool = object : HaTool() {
        override val spec = ToolSpec(
            name = "home_states",
            description = "Look up smart home devices and sensors in Home Assistant and their current state (on/off, " +
                "brightness, temperature, position...). With no arguments, lists controllable devices.",
            parameters = objectSchema(
                emptyList(),
                "query" to stringProperty("Words to match in the device name, room or entity id, e.g. 'bedroom', 'temperature'"),
                "domain" to stringProperty("Only this kind of entity, e.g. 'light', 'sensor', 'climate'"),
            ),
        )

        override suspend fun run(ha: HomeAssistant, args: JsonObject): ToolOutcome {
            val areas = ha.areas()
            val matches = HaEntity.filter(ha.states(), areas, args.string("query"), args.string("domain"))
            if (matches.isEmpty()) return ToolOutcome("Nothing in Home Assistant matches.")
            val shown = matches.take(LIMIT)
            val more = if (matches.size > shown.size) "\n(${matches.size - shown.size} more; narrow the query.)" else ""
            return ToolOutcome(shown.joinToString("\n") { it.describe(areas[it.entityId]) } + more)
        }
    }

    val callService: Tool = object : HaTool() {
        override val spec = ToolSpec(
            name = "home_call_service",
            description = "Control smart home devices through Home Assistant. Target a whole room with area (e.g. " +
                "area 'living room', service 'light.turn_off'), or devices by name or entity id (e.g. entities " +
                "'Sunset lamp', service 'turn_on'), or entities 'all' with a domain.service. E.g. 'turn_on' with data " +
                "{\"brightness_pct\": 40, \"color_name\": \"blue\"}, 'climate.set_temperature' with {\"temperature\": 21}, " +
                "'cover.open_cover', 'scene.turn_on'. The result is the devices' state afterwards. Unlocking, disarming an " +
                "alarm or opening a garage or gate is held until the user confirms: the result tells you what to ask.",
            parameters = objectSchema(
                listOf("service"),
                "service" to stringProperty("'domain.service' (required with area or 'all'), or just the service, e.g. 'turn_on'"),
                "entities" to stringProperty("Device names as the user said them or entity ids, separated by commas, or 'all'"),
                "area" to stringProperty("A room or area, e.g. 'living room'"),
                "data" to buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Service data besides the target, e.g. {\"brightness_pct\": 40}"))
                },
            ),
        )

        override suspend fun run(ha: HomeAssistant, args: JsonObject): ToolOutcome {
            // HA lowercases these itself, so the safety check must see what HA will run.
            val requested = args.requireString("service").trim().lowercase()
            val names = args.string("entities")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val area = args.string("area")
            val explicitDomain = requested.substringBefore('.', "").ifEmpty { null }
            val service = requested.substringAfter('.')
            val data = (args["data"] as? JsonObject) ?: JsonObject(emptyMap())
            // HA would add these to the targets, past the ones checked below.
            HaSafety.TARGET_KEYS.firstOrNull { it in data }?.let {
                return ToolOutcome("Not done: '$it' doesn't go in data. Name the targets with entities or area.")
            }

            val needAreas = area != null || names.any { '.' !in it }
            val resolution = coroutineScope {
                val states = async { ha.states() }
                val areas = if (needAreas) ha.areas() else emptyMap()
                HaTargets.resolve(states.await(), areas, names, area, explicitDomain)
            }
            val (targets, skipped) = when (resolution) {
                is HaTargets.Resolution.Problem -> return ToolOutcome("Not done: ${resolution.message}")
                is HaTargets.Resolution.Found -> resolution.entities to resolution.skipped
            }
            val perform: suspend () -> ToolOutcome = {
                // One call per domain, so a bare service ("turn_off") works across lights and switches.
                val byDomain = targets.groupBy { explicitDomain ?: it.domain }
                val reported = mutableMapOf<String, HaEntity>()
                for ((domain, group) in byDomain) {
                    val body = JsonObject(data + ("entity_id" to JsonArray(group.map { JsonPrimitive(it.entityId) })))
                    ha.callService(domain, service, body).forEach { reported[it.entityId] = it }
                }
                val after = settle(ha, targets, reported, service.takeIf { data.isEmpty() }?.let(HaTargets::targetState))

                val what = "$requested on " + (area?.let { "${targets.size} in $it" } ?: targets.joinToString { it.name })
                val lines = targets.map { before ->
                    val now = after[before.entityId]
                    if (now != null) now.describe() else "${before.describe()} (not updated yet; the device may still be changing)"
                }
                val skippedNote = if (skipped.isEmpty()) "" else "\nSkipped, unavailable: ${skipped.joinToString { it.name }}"
                ToolOutcome("Called $what. Now:\n" + lines.joinToString("\n") + skippedNote)
            }

            val sensitive = targets.filter { HaSafety.needsConfirmation(explicitDomain ?: it.domain, service, it) }
            if (sensitive.isEmpty()) return perform()
            val what = "${service.replace('_', ' ')} ${sensitive.joinToString { it.name }}"
            return ToolOutcome(
                "Not done yet: $what needs the user's confirmation. Ask them.",
                pending = PendingAction(what, needsUnlock = true) { reportingErrors(perform) },
            )
        }
    }

    /**
     * Each target's state once it has caught up with the call. HA returns what changed while the
     * service ran, but many devices (and wrappers like switch-as-light) report a moment later, so
     * the rest are polled briefly. Entities already in [expected] don't wait. Missing from the
     * result: still unchanged after the wait.
     */
    private suspend fun settle(ha: HomeAssistant, targets: List<HaEntity>, reported: Map<String, HaEntity>, expected: String?): Map<String, HaEntity> {
        val done = reported.filterKeys { id -> targets.any { it.entityId == id } }.toMutableMap()
        targets.filter { it.entityId !in done && expected != null && it.state == expected }.forEach { done[it.entityId] = it }
        var pending = targets.filter { it.entityId !in done }
        repeat(SETTLE_MS / POLL_MS) {
            if (pending.isEmpty()) return done
            delay(POLL_MS.toLong())
            val latest = coroutineScope { pending.map { e -> async { e to ha.state(e.entityId) } }.awaitAll() }
            latest.forEach { (before, now) ->
                if (now != null && (now.state != before.state || now.attributes != before.attributes)) done[now.entityId] = now
            }
            pending = pending.filter { it.entityId !in done }
        }
        return done
    }

    private companion object {
        const val LIMIT = 60
        const val SETTLE_MS = 3000
        const val POLL_MS = 250
    }
}
