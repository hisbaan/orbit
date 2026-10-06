package com.hisbaan.orbit.homeassistant

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** How Orbit authenticates to Home Assistant. */
sealed interface HaCredential {
    /** A long-lived access token made in the user's HA profile. */
    data class LongLived(val token: String) : HaCredential

    /** OAuth sign-in: the refresh token is kept, access tokens (30 min) are minted from it. */
    data class OAuth(val refreshToken: String) : HaCredential
}

class HaException(message: String, val status: Int? = null) : Exception(message) {
    /** The credential no longer works; the user must sign in again. */
    val unauthorized: Boolean get() = status == 401 || status == 403
}

/**
 * Home Assistant's OAuth (IndieAuth flavour: no client registration). HA accepts a client id
 * without fetching it when the redirect URI has the same scheme and host, so Orbit uses the
 * instance's own origin for both and catches the redirect in its sign-in web view before it
 * loads. Nothing has to be hosted and it works on a LAN without internet.
 */
object HaAuth {
    fun origin(baseUrl: String): String {
        val url = Url(baseUrl.trim())
        return URLBuilder(protocol = url.protocol, host = url.host, port = url.port).buildString().trimEnd('/')
    }

    fun clientId(baseUrl: String) = origin(baseUrl) + "/"
    fun redirectUri(baseUrl: String) = origin(baseUrl) + "/orbit-auth-callback"

    fun authorizeUrl(baseUrl: String, state: String): String = URLBuilder(origin(baseUrl) + "/auth/authorize").apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", clientId(baseUrl))
        parameters.append("redirect_uri", redirectUri(baseUrl))
        parameters.append("state", state)
    }.buildString()

    /** The code from a redirect to [redirectUri], or null if [url] isn't that redirect. */
    fun codeFromRedirect(baseUrl: String, url: String, expectedState: String): Result<String>? {
        if (!url.startsWith(redirectUri(baseUrl))) return null
        val params = Url(url).parameters
        if (params["state"] != expectedState) return Result.failure(HaException("Sign-in state didn't match; try again."))
        params["error"]?.let { return Result.failure(HaException("Sign-in failed: $it")) }
        return params["code"]?.let { Result.success(it) } ?: Result.failure(HaException("Sign-in returned no code."))
    }

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

    suspend fun exchangeCode(http: HttpClient, baseUrl: String, code: String): Tokens = token(
        http,
        baseUrl,
        parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("client_id", clientId(baseUrl))
        },
    )

    suspend fun refresh(http: HttpClient, baseUrl: String, refreshToken: String): Tokens = token(
        http,
        baseUrl,
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId(baseUrl))
        },
    )

    /** Deletes the refresh token (and its access tokens) on the server. */
    suspend fun revoke(http: HttpClient, baseUrl: String, refreshToken: String) {
        http.submitForm(origin(baseUrl) + "/auth/revoke", parameters { append("token", refreshToken) })
    }

    private suspend fun token(http: HttpClient, baseUrl: String, form: io.ktor.http.Parameters): Tokens {
        val response = http.submitForm(origin(baseUrl) + "/auth/token", form)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // A revoked or expired refresh token comes back as 400 invalid_grant.
            val status = if (response.status == HttpStatusCode.BadRequest && "invalid_grant" in body) 401 else response.status.value
            throw HaException("Home Assistant sign-in failed (HTTP ${response.status.value}): ${body.take(200)}", status)
        }
        val json = Json.parseToJsonElement(body).jsonObject
        return Tokens(
            accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull ?: throw HaException("No access token in response"),
            refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull,
            expiresInSeconds = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800,
        )
    }
}

data class HaConversation(val speech: String, val responseType: String, val errorCode: String?)

/** A Home Assistant instance's REST API. Access tokens from OAuth are cached and refreshed on demand. */
class HomeAssistant(
    private val http: HttpClient,
    baseUrl: String,
    private val credential: HaCredential,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val origin = HaAuth.origin(baseUrl)
    private val tokenLock = Mutex()
    private var accessToken: String? = (credential as? HaCredential.LongLived)?.token
    private var expiresAt = Long.MAX_VALUE

    /** The instance's name ("Home"), which also proves the credential works. */
    suspend fun locationName(): String =
        json.parseToJsonElement(get("/api/config")).jsonObject["location_name"]?.jsonPrimitive?.contentOrNull ?: "Home Assistant"

    suspend fun states(): List<HaEntity> =
        (json.parseToJsonElement(get("/api/states")) as JsonArray).mapNotNull { (it as? JsonObject)?.let(HaEntity::parse) }

    suspend fun state(entityId: String): HaEntity? =
        runCatching { HaEntity.parse(json.parseToJsonElement(get("/api/states/$entityId")).jsonObject) }.getOrNull()

    /** entity_id → area name, for entities that have one. */
    suspend fun areas(): Map<String, String> {
        val template = "{% for s in states %}{% set a = area_name(s.entity_id) %}{% if a %}{{ s.entity_id }}|{{ a }}\n{% endif %}{% endfor %}"
        val text = post("/api/template", buildJsonObject { put("template", template) })
        return text.lineSequence().mapNotNull { line ->
            line.split('|', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() }
        }.toMap()
    }

    /** Calls a service; returns the entities whose state changed. */
    suspend fun callService(domain: String, service: String, data: JsonObject): List<HaEntity> {
        val body = post("/api/services/$domain/$service", data)
        return (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(HaEntity::parse) }
    }

    /** Hands [text] to HA's built-in Assist agent, which knows the user's names, aliases and areas. */
    suspend fun converse(text: String, language: String): HaConversation {
        val body = post(
            "/api/conversation/process",
            buildJsonObject {
                put("text", text)
                put("language", language)
                put("agent_id", "conversation.home_assistant")
            },
        )
        val response = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject
        val speech = response?.get("speech")?.jsonObject?.get("plain")?.jsonObject?.get("speech")?.jsonPrimitive?.contentOrNull.orEmpty()
        return HaConversation(
            speech = speech,
            responseType = response?.get("response_type")?.jsonPrimitive?.contentOrNull ?: "unknown",
            errorCode = response?.get("data")?.jsonObject?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    private suspend fun get(path: String): String = authorized { token -> http.get(origin + path) { header("Authorization", "Bearer $token") } }

    private suspend fun post(path: String, body: JsonObject): String = authorized { token ->
        http.post(origin + path) {
            header("Authorization", "Bearer $token")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
    }

    /** Runs [request] with a valid token; on a 401 with OAuth, refreshes once and retries. */
    private suspend fun authorized(request: suspend (String) -> HttpResponse): String {
        var response = request(token(forceRefresh = false))
        if (response.status == HttpStatusCode.Unauthorized && credential is HaCredential.OAuth) {
            response = request(token(forceRefresh = true))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw HaException("Home Assistant: HTTP ${response.status.value} ${body.take(200)}", response.status.value)
        return body
    }

    private suspend fun token(forceRefresh: Boolean): String = tokenLock.withLock {
        val current = accessToken
        if (credential is HaCredential.LongLived) return credential.token
        if (current != null && !forceRefresh && clock() < expiresAt) return current
        val refreshToken = (credential as HaCredential.OAuth).refreshToken
        val tokens = HaAuth.refresh(http, origin, refreshToken)
        accessToken = tokens.accessToken
        // Refresh a minute early so a request never races the expiry.
        expiresAt = clock() + (tokens.expiresInSeconds - 60).coerceAtLeast(30) * 1000
        tokens.accessToken
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
