package com.hisbaan.orbit.homeassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    private class Server(val handler: suspend MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData) {
        val log = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                log += "${request.method.value} ${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization] ?: ""}".trim()
                handler(request, body)
            },
        )
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `oauth urls stay on the instance origin`() {
        assertEquals("http://192.168.1.5:8123/", HaAuth.clientId("http://192.168.1.5:8123/lovelace/0"))
        assertEquals("https://ha.example.com/orbit-auth-callback", HaAuth.redirectUri("https://ha.example.com"))
        val url = HaAuth.authorizeUrl("https://ha.example.com/", "s1")
        assertTrue(url, url.startsWith("https://ha.example.com/auth/authorize?response_type=code&client_id=https%3A%2F%2Fha.example.com%2F"))
        assertEquals("abc", HaAuth.codeFromRedirect("https://ha.example.com", "https://ha.example.com/orbit-auth-callback?code=abc&state=s1", "s1")!!.getOrThrow())
        assertTrue(HaAuth.codeFromRedirect("https://ha.example.com", "https://ha.example.com/orbit-auth-callback?code=abc&state=x", "s1")!!.isFailure)
        assertNull(HaAuth.codeFromRedirect("https://ha.example.com", "https://ha.example.com/auth/authorize", "s1"))
    }

    @Test
    fun `mints, reuses and refreshes oauth access tokens`() = runTest {
        var now = 0L
        var minted = 0
        val server = Server { request, body ->
            when (request.url.encodedPath) {
                "/auth/token" -> {
                    assertTrue(body, "grant_type=refresh_token" in body && "refresh_token=r1" in body && "client_id=https%3A%2F%2Fha.test%2F" in body)
                    minted++
                    json("""{"access_token":"a$minted","expires_in":1800,"token_type":"Bearer"}""")
                }
                "/api/config" -> json("""{"location_name":"Home"}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val ha = HomeAssistant(server.client, "https://ha.test", HaCredential.OAuth("r1"), clock = { now })

        assertEquals("Home", ha.locationName())
        assertEquals("Home", ha.locationName())
        now = 1_800_000 // past expiry
        ha.locationName()

        assertEquals(
            listOf("POST /auth/token", "GET /api/config Bearer a1", "GET /api/config Bearer a1", "POST /auth/token", "GET /api/config Bearer a2"),
            server.log,
        )
    }

    @Test
    fun `retries once with a fresh token after a 401`() = runTest {
        var minted = 0
        val server = Server { request, _ ->
            when (request.url.encodedPath) {
                "/auth/token" -> json("""{"access_token":"a${++minted}","expires_in":1800}""")
                else -> if (request.headers[HttpHeaders.Authorization] == "Bearer a1") json("{}", HttpStatusCode.Unauthorized) else json("""{"location_name":"Home"}""")
            }
        }
        assertEquals("Home", HomeAssistant(server.client, "https://ha.test", HaCredential.OAuth("r1")).locationName())
        assertEquals(2, minted)
    }

    @Test
    fun `revoked refresh token reads as unauthorized`() = runTest {
        val server = Server { _, _ -> json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
        val error = runCatching { HomeAssistant(server.client, "https://ha.test", HaCredential.OAuth("gone")).locationName() }.exceptionOrNull()
        assertTrue(error is HaException && error.unauthorized)
    }

    private val statesJson = """[
        {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen light","brightness":178}},
        {"entity_id":"light.bedroom","state":"off","attributes":{"friendly_name":"Bedroom lamp"}},
        {"entity_id":"sensor.bedroom_temperature","state":"20.5","attributes":{"friendly_name":"Bedroom temperature","unit_of_measurement":"°C"}},
        {"entity_id":"lock.front_door","state":"locked","attributes":{"friendly_name":"Front door"}},
        {"entity_id":"cover.garage","state":"closed","attributes":{"friendly_name":"Garage door","device_class":"garage"}}
    ]"""

    private val livingRoomJson = """[
        {"entity_id":"light.sunset_lamp","state":"off","attributes":{"friendly_name":"Sunset lamp"}},
        {"entity_id":"switch.sunset_lamp_switch","state":"off","attributes":{"friendly_name":"Sunset lamp switch"}},
        {"entity_id":"sensor.sunset_lamp_switch_power","state":"0","attributes":{"friendly_name":"Sunset lamp switch Power","unit_of_measurement":"W"}},
        {"entity_id":"light.mushroom_lamp","state":"on","attributes":{"friendly_name":"Mushroom lamp","brightness":255}},
        {"entity_id":"light.donut_lamp","state":"unavailable","attributes":{"friendly_name":"Donut lamp"}},
        {"entity_id":"light.kitchen","state":"off","attributes":{"friendly_name":"Kitchen light"}}
    ]"""

    /**
     * A small HA: states live in a map, service calls flip on/off. Entities in [lagging] change
     * only after a few more state reads (a device that reports late), so the call reports nothing.
     */
    private class FakeHa(
        statesJson: String,
        private val areas: Map<String, String>,
        private val lagging: Set<String> = emptySet(),
        private val lagReads: Int = 3,
    ) {
        val states = Json.parseToJsonElement(statesJson).jsonArray.associateBy { it.jsonObject["entity_id"]!!.jsonPrimitive.content }
            .mapValues { it.value.jsonObject }.toMutableMap()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private val due = mutableMapOf<String, Pair<Int, String>>()

        private fun withState(id: String, state: String) =
            JsonObject(states.getValue(id) + ("state" to JsonPrimitive(state))).also { states[id] = it }

        val server = Server { request, body ->
            val path = request.url.encodedPath
            when {
                path == "/api/states" -> json(JsonArray(states.values.toList()).toString())
                path.startsWith("/api/states/") -> {
                    val id = path.removePrefix("/api/states/")
                    due[id]?.let { (reads, state) -> if (reads <= 1) { due -= id; withState(id, state) } else due[id] = reads - 1 to state }
                    json(states.getValue(id).toString())
                }
                path == "/api/template" -> respond(areas.entries.joinToString("\n") { "${it.key}|${it.value}" })
                path.startsWith("/api/services/") -> {
                    val service = path.removePrefix("/api/services/")
                    val payload = Json.parseToJsonElement(body).jsonObject
                    calls += service to payload
                    val target = if (service.endsWith("turn_on")) "on" else "off"
                    val changed = payload["entity_id"]!!.jsonArray.map { it.jsonPrimitive.content }.mapNotNull { id ->
                        if (id in lagging) { due[id] = lagReads to target; null } else withState(id, target)
                    }
                    json(JsonArray(changed).toString())
                }
                else -> error("unexpected $path")
            }
        }

        fun tools() = HomeTools({ HomeAssistant(server.client, "https://ha.test", HaCredential.LongLived("t")) }, { "en" })

        private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun livingRoom(lagging: Set<String> = emptySet(), lagReads: Int = 3) = FakeHa(
        livingRoomJson,
        mapOf(
            "light.sunset_lamp" to "Living Room", "switch.sunset_lamp_switch" to "Living Room",
            "sensor.sunset_lamp_switch_power" to "Living Room", "light.mushroom_lamp" to "Living Room",
            "light.donut_lamp" to "Living Room", "light.kitchen" to "Kitchen",
        ),
        lagging,
        lagReads,
    )

    private fun haServer(calls: MutableList<Pair<String, JsonObject>> = mutableListOf()) = Server { request, body ->
        val path = request.url.encodedPath
        when {
            path == "/api/states" -> json(statesJson)
            path.startsWith("/api/states/") -> {
                val id = path.removePrefix("/api/states/")
                json(Json.parseToJsonElement(statesJson).jsonArray.first { it.jsonObject["entity_id"] == JsonPrimitive(id) }.toString())
            }
            path == "/api/template" -> respond("light.kitchen|Kitchen\nlight.bedroom|Bedroom\nsensor.bedroom_temperature|Bedroom\n")
            path.startsWith("/api/services/") -> {
                calls += path.removePrefix("/api/services/") to Json.parseToJsonElement(body).jsonObject
                json("""[{"entity_id":"light.bedroom","state":"on","attributes":{"friendly_name":"Bedroom lamp","brightness":102}}]""")
            }
            path == "/api/conversation/process" -> json(
                """{"response":{"response_type":"error","speech":{"plain":{"speech":"Sorry, I am not aware of any device called garden"}},"data":{"code":"no_valid_targets"}}}""",
            )
            else -> error("unexpected $path")
        }
    }

    private fun tools(server: Server) = HomeTools({ HomeAssistant(server.client, "https://ha.test", HaCredential.LongLived("t")) }, { "en" })

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `lists controllable devices by default and matches rooms`() = runTest {
        val tools = tools(haServer())
        val all = tools.states.invoke(args("{}")).result
        assertEquals(
            """
            light.kitchen "Kitchen light" [Kitchen]: on, brightness 70%
            light.bedroom "Bedroom lamp" [Bedroom]: off
            lock.front_door "Front door": locked
            cover.garage "Garage door": closed
            """.trimIndent(),
            all,
        )
        val bedroom = tools.states.invoke(args("""{"query":"bedroom"}""")).result
        assertEquals(
            """
            light.bedroom "Bedroom lamp" [Bedroom]: off
            sensor.bedroom_temperature "Bedroom temperature" [Bedroom]: 20.5 °C
            """.trimIndent(),
            bedroom,
        )
    }

    @Test
    fun `calls services and reports the new state`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val result = tools(haServer(calls)).callService.invoke(
            args("""{"entities":"light.bedroom","service":"turn_on","data":{"brightness_pct":40}}"""),
        ).result
        assertEquals("light/turn_on", calls.single().first)
        assertEquals(args("""{"brightness_pct":40,"entity_id":["light.bedroom"]}"""), calls.single().second)
        assertEquals("Called turn_on on Bedroom lamp. Now:\nlight.bedroom \"Bedroom lamp\": on, brightness 40%", result)
    }

    @Test
    fun `sensitive actions need confirmation`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val tools = tools(haServer(calls))
        val unlock = tools.callService.invoke(args("""{"entities":"lock.front_door","service":"unlock"}"""))
        val garage = tools.callService.invoke(args("""{"entities":"Garage door","service":"open_cover"}"""))
        assertTrue(unlock.result, unlock.result.startsWith("Not done yet"))
        assertTrue(garage.result, garage.result.startsWith("Not done yet"))
        // Confirming is the agent's job: the tool only holds the action back.
        assertTrue(calls.isEmpty())
        unlock.pending!!.run()
        assertEquals("lock/unlock", calls.single().first)
    }

    @Test
    fun `assist failures point the model at the other tools`() = runTest {
        val result = tools(haServer()).command.invoke(args("""{"text":"turn on the garden lights"}""")).result
        assertTrue(result, result.startsWith("Home Assistant didn't do it (no_valid_targets)"))
    }

    @Test
    fun `an area turns on every light in it, not just exposed ones`() = runTest {
        val ha = livingRoom()
        val result = ha.tools().callService.invoke(args("""{"area":"the living room","service":"light.turn_on"}""")).result
        assertEquals("light/turn_on", ha.calls.single().first)
        assertEquals(listOf("light.sunset_lamp", "light.mushroom_lamp"), ha.calls.single().second["entity_id"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            """
            Called light.turn_on on 2 in the living room. Now:
            light.sunset_lamp "Sunset lamp": on
            light.mushroom_lamp "Mushroom lamp": on, brightness 100%
            Skipped, unavailable: Donut lamp
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `names resolve to the controllable entity`() = runTest {
        val ha = livingRoom()
        ha.tools().callService.invoke(args("""{"entities":"sunset lamp","service":"turn_on"}"""))
        assertEquals("light/turn_on", ha.calls.single().first)
        assertEquals(args("""{"entity_id":["light.sunset_lamp"]}"""), ha.calls.single().second)
    }

    @Test
    fun `unknown areas and ambiguous names come back to the model`() = runTest {
        val tools = livingRoom().tools()
        val area = tools.callService.invoke(args("""{"area":"garden","service":"light.turn_on"}""")).result
        assertEquals("Not done: No area called 'garden'. Areas: Kitchen, Living Room.", area)
        val lamp = tools.callService.invoke(args("""{"entities":"lamp","service":"turn_on"}""")).result
        assertTrue(lamp, lamp.startsWith("Not done: 'lamp' matches several: light.sunset_lamp"))
        val noDomain = tools.callService.invoke(args("""{"area":"kitchen","service":"turn_on"}""")).result
        assertTrue(noDomain, noDomain.contains("domain.service"))
    }

    @Test
    fun `waits for a device that reports its new state late`() = runTest {
        val ha = livingRoom(lagging = setOf("light.sunset_lamp"))
        val result = ha.tools().callService.invoke(args("""{"entities":"Sunset lamp","service":"turn_on"}""")).result
        assertEquals("Called turn_on on Sunset lamp. Now:\nlight.sunset_lamp \"Sunset lamp\": on", result)
    }

    @Test
    fun `says when a device hasn't caught up yet`() = runTest {
        val ha = livingRoom(lagging = setOf("light.sunset_lamp"), lagReads = 100)
        val result = ha.tools().callService.invoke(args("""{"entities":"Sunset lamp","service":"turn_on"}""")).result
        assertEquals(
            "Called turn_on on Sunset lamp. Now:\nlight.sunset_lamp \"Sunset lamp\": off (not updated yet; the device may still be changing)",
            result,
        )
    }

    @Test
    fun `devices already in the requested state don't wait`() = runTest {
        val ha = livingRoom(lagging = setOf("light.mushroom_lamp"))
        val result = ha.tools().callService.invoke(args("""{"entities":"light.mushroom_lamp","service":"turn_on"}""")).result
        assertEquals("Called turn_on on Mushroom lamp. Now:\nlight.mushroom_lamp \"Mushroom lamp\": on, brightness 100%", result)
    }
}
