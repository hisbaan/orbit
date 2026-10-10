package com.hisbaan.orbit.websearch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Field readers for the providers' responses: a missing field, a null, or a value of another
// type reads as absent rather than throwing.

internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
