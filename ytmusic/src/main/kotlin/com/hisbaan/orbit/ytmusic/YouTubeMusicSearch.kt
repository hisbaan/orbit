package com.hisbaan.orbit.ytmusic

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class YtmResult(
    val type: Type,
    val title: String,
    /** Everything after the type, e.g. "Queen • 2.7B plays". */
    val subtitle: String,
    val videoId: String?,
    val playlistId: String?,
) {
    enum class Type { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST, OTHER }

    /** A link YouTube Music starts playing when opened (`playlist?list=` only shows the page). */
    val playUrl: String?
        get() = when {
            videoId != null -> "https://music.youtube.com/watch?v=$videoId"
            playlistId != null -> "https://music.youtube.com/watch?list=$playlistId"
            else -> null
        }

    fun describe(): String = "${type.name.lowercase()} '$title'${if (subtitle.isNotBlank()) " ($subtitle)" else ""}"
}

/**
 * Search against YouTube Music's own catalog via the internal API its website uses (the
 * approach of the ytmusicapi project). Unofficial: no key or account, but Google can change it
 * at any time. Used because YouTube Music only accepts `playFromSearch` from allowlisted apps
 * (see PLAN.md), while it does play `music.youtube.com/watch` links.
 */
class YouTubeMusicSearch(
    private val client: HttpClient,
    private val today: () -> LocalDate = LocalDate::now,
) {
    suspend fun search(query: String, language: String = "en", country: String = "US"): List<YtmResult> {
        val body = buildJsonObject {
            put(
                "context",
                buildJsonObject {
                    put(
                        "client",
                        buildJsonObject {
                            put("clientName", "WEB_REMIX")
                            // The web client's version is its build date; a recent one is accepted.
                            put("clientVersion", "1.${today().minusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE)}.01.00")
                            put("hl", language)
                            put("gl", country)
                        },
                    )
                },
            )
            put("query", query)
        }
        val response = client.post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false") {
            header("Origin", "https://music.youtube.com")
            header("User-Agent", USER_AGENT)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) throw IllegalStateException("YouTube Music search failed: HTTP ${response.status.value}")
        return parse(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
        private val json = Json { ignoreUnknownKeys = true }

        /** Results in the order YouTube Music ranks them, top result first. */
        fun parse(root: JsonObject): List<YtmResult> {
            val results = mutableListOf<YtmResult>()
            findResults(root) { key, value ->
                when (key) {
                    "musicCardShelfRenderer" -> parseCard(value)?.let(results::add)
                    "musicResponsiveListItemRenderer" -> parseListItem(value)?.let(results::add)
                }
            }
            return results
        }

        /**
         * Picks what to play. [kind] narrows by type ("song" also accepts videos, after songs);
         * [artist] prefers results crediting that artist. Falls back to the top result.
         */
        fun pick(results: List<YtmResult>, kind: String?, artist: String?): YtmResult? {
            val playable = results.filter { it.playUrl != null && it.type != YtmResult.Type.OTHER }
            val byKind = when (kind) {
                "song" -> playable.filter { it.type == YtmResult.Type.SONG } + playable.filter { it.type == YtmResult.Type.VIDEO }
                "album" -> playable.filter { it.type == YtmResult.Type.ALBUM }
                "artist" -> playable.filter { it.type == YtmResult.Type.ARTIST }
                "playlist" -> playable.filter { it.type == YtmResult.Type.PLAYLIST }
                else -> playable
            }.ifEmpty { playable }
            val name = artist?.trim()?.takeIf { it.isNotEmpty() } ?: return byKind.firstOrNull()
            // Exact-case credits first: "EDEN" the artist vs "Eden" the Dutch singer.
            return byKind.firstOrNull { name in it.credits() }
                ?: byKind.firstOrNull { r -> r.credits().any { it.equals(name, ignoreCase = true) } }
                ?: byKind.firstOrNull { it.subtitle.contains(name, ignoreCase = true) }
                ?: byKind.firstOrNull()
        }

        /** Subtitle segments, which hold the artist credit(s) ("Queen", "2.7B plays", ...). */
        private fun YtmResult.credits(): List<String> =
            subtitle.split(" • ").flatMap { it.split(", ", " & ") }.map { it.trim() } + listOf(title).filter { type == YtmResult.Type.ARTIST }

        private fun parseCard(card: JsonObject): YtmResult? {
            val titleRuns = card["title"]?.obj()?.get("runs")?.jsonArray
            val title = runsText(card["title"])
            val (type, subtitle) = typeAndSubtitle(runsText(card["subtitle"]))
            val titleWatch = titleRuns?.firstOrNull()?.obj()?.get("navigationEndpoint")?.obj()?.get("watchEndpoint")?.obj()
            return target(card, type, titleWatch)?.let { (videoId, playlistId) -> YtmResult(type, title, subtitle, videoId, playlistId) }
        }

        private fun parseListItem(item: JsonObject): YtmResult? {
            val columns = item["flexColumns"]?.jsonArray.orEmpty().map {
                runsText(it.obj()?.get("musicResponsiveListItemFlexColumnRenderer")?.obj()?.get("text"))
            }
            val title = columns.getOrNull(0).orEmpty()
            val (type, subtitle) = typeAndSubtitle(columns.drop(1).filter { it.isNotBlank() }.joinToString(" • "))
            val itemWatch = item["playlistItemData"]?.obj()?.get("videoId")?.string()?.let { id ->
                buildJsonObject { put("videoId", id) }
            }
            return target(item, type, itemWatch)?.let { (videoId, playlistId) -> YtmResult(type, title, subtitle, videoId, playlistId) }
        }

        /**
         * Songs and videos play by video id; albums, artists (radio) and playlists by playlist id.
         * [preferred] is the item's own watch endpoint, which beats nested ones (a top-result
         * card also contains other songs).
         */
        private fun target(node: JsonObject, type: YtmResult.Type, preferred: JsonObject?): Pair<String?, String?>? = when (type) {
            YtmResult.Type.SONG, YtmResult.Type.VIDEO -> {
                val videoId = preferred?.get("videoId")?.string()
                    ?: first(node, "watchEndpoint")?.get("videoId")?.string()
                videoId?.let { it to null }
            }
            YtmResult.Type.ALBUM, YtmResult.Type.ARTIST, YtmResult.Type.PLAYLIST -> {
                val playlistId = first(node, "watchPlaylistEndpoint")?.get("playlistId")?.string()
                    ?: first(node, "watchEndpoint")?.get("playlistId")?.string()
                playlistId?.let { null to it }
            }
            YtmResult.Type.OTHER -> null
        }

        /** "Song • Queen • 2.7B plays" → SONG, "Queen • 2.7B plays". */
        private fun typeAndSubtitle(text: String): Pair<YtmResult.Type, String> {
            val head = text.substringBefore(" • ").trim()
            val type = when (head.lowercase()) {
                "song" -> YtmResult.Type.SONG
                "video" -> YtmResult.Type.VIDEO
                "album", "single", "ep" -> YtmResult.Type.ALBUM
                "artist" -> YtmResult.Type.ARTIST
                "playlist" -> YtmResult.Type.PLAYLIST
                else -> YtmResult.Type.OTHER
            }
            return type to (if (type == YtmResult.Type.OTHER) text else text.substringAfter(" • ", ""))
        }

        private fun runsText(element: JsonElement?): String =
            element?.obj()?.get("runs")?.jsonArray.orEmpty().joinToString("") { it.obj()?.get("text")?.string().orEmpty() }

        /** Calls [visit] for each result renderer, in document order, without descending into it. */
        private fun findResults(node: JsonElement, visit: (String, JsonObject) -> Unit) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (value is JsonObject && key in RESULT_KEYS) visit(key, value) else findResults(value, visit)
                }
                is JsonArray -> node.forEach { findResults(it, visit) }
                else -> Unit
            }
        }

        /** First object under [key] below [node]: a direct child wins, then children in document order. */
        private fun first(node: JsonElement, key: String): JsonObject? = when (node) {
            is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { first(it, key) }
            is JsonArray -> node.firstNotNullOfOrNull { first(it, key) }
            else -> null
        }

        private val RESULT_KEYS = setOf("musicCardShelfRenderer", "musicResponsiveListItemRenderer")

        private fun JsonElement.obj(): JsonObject? = this as? JsonObject
        private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
