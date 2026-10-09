package com.hisbaan.orbit.agent

import com.hisbaan.orbit.providers.ChatImage
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface Tool {
    val spec: ToolSpec

    /** False hides the tool from the model, e.g. while the service behind it isn't set up. */
    val available: Boolean get() = true

    /**
     * The tool performs an action and reports [ToolOutcome.done] when it goes through. The
     * agent asks the model, in an extra argument, for the sentence to say if it works, so the
     * turn can end without a second model call. See [Agent].
     */
    val confirms: Boolean get() = false

    /**
     * The result holds other people's words or what's on screen (notifications, screen text),
     * so the agent never logs it, only its size, even where the log keeps content.
     */
    val privateResult: Boolean get() = false

    /**
     * Reads or acts on the user's private data (notifications, the screen, the calendar,
     * contacts), so it only runs on an unlocked phone: someone holding a locked phone mustn't
     * get at it by voice. The agent asks the user to unlock first. See [Agent].
     */
    val needsUnlock: Boolean get() = false

    /** Runs the tool. Throwing is fine: the agent reports the error back to the model. */
    suspend fun invoke(args: JsonObject): ToolOutcome
}

/**
 * [result] goes back to the model. [afterTurn] is work deferred past the tool call: anything
 * that starts its own audio, opens another app or places a call. See PLAN.md, Phase 1 "Tool
 * timing".
 *
 * [done]: the action went through and the model has nothing to learn from [result], so the
 * confirmation it wrote into the call can be the whole reply (see [Tool.confirms]).
 *
 * [images] (e.g. a screenshot) reach the model in a message after the tool results, since
 * tool results themselves are text only. [cards] are shown to the user on screen.
 *
 * [pending]: an action that needs the user's yes first (dialing, deleting, unlocking), held
 * back instead of done; [result] then tells the model what to ask. Never [done].
 */
data class ToolOutcome(
    val result: String,
    val afterTurn: AfterTurnAction? = null,
    val done: Boolean = false,
    val images: List<ChatImage> = emptyList(),
    val cards: List<Card> = emptyList(),
    val pending: PendingAction? = null,
)

/**
 * An action waiting for the user's yes, resolved to its exact target (the number, the event,
 * the entities). The agent holds it under a ref and runs [run] only when the model calls
 * [Agent.CONFIRM_TOOL] with that ref in the user's next message, so neither the model nor text
 * it reads (notifications, the screen) can confirm on the user's behalf, and what runs is what
 * was read back.
 */
class PendingAction(
    val description: String,
    /** Security-sensitive (unlocking a door): the yes only counts on an unlocked phone. */
    val needsUnlock: Boolean = false,
    val run: suspend () -> ToolOutcome,
)

class AfterTurnAction(
    val description: String,
    /** Opens another app's UI, so the device must be unlocked first. */
    val needsUnlock: Boolean,
    /**
     * Makes no sound of its own (opening an app, starting navigation), so it starts as soon
     * as the reply does instead of waiting for the turn to end and the headset to be released.
     */
    val duringReply: Boolean = false,
    val run: suspend () -> Unit,
)

// region JSON Schema helpers

fun objectSchema(required: List<String> = emptyList(), vararg properties: Pair<String, JsonObject>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

fun stringProperty(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
}

fun integerProperty(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
}

fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

fun JsonObject.boolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

fun JsonObject.requireString(name: String): String =
    string(name) ?: throw IllegalArgumentException("Missing required argument '$name'")

// endregion
