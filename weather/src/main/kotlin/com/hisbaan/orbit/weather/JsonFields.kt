package com.hisbaan.orbit.weather

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

// Field readers for the providers' responses: a missing field, a null, or a value of another
// type reads as absent rather than throwing.

internal fun JsonObject.obj(key: String) = get(key) as? JsonObject

internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: num(key)?.toLong()

/** A coordinate for a request: plain decimals (`toString` gives "1.0E-4" near 0), about a metre's precision. */
internal fun coordinate(value: Double): String = "%.5f".format(Locale.ROOT, value)
