package com.hisbaan.orbit.websearch

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * A SearXNG instance (docs.searxng.org): a self-hosted metasearch engine that queries other
 * engines for you. No key; the instance must allow JSON output (`search: formats: [html, json]`
 * in its settings.yml), which it doesn't by default.
 */
class SearXng(private val client: HttpClient, baseUrl: String) : SearchProvider {
    override val name = "SearXNG"

    /** The instance's root, accepting a pasted search URL ("https://search.example.com/search"). */
    private val root = baseUrl.trim().trimEnd('/').removeSuffix("/search")

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> {
        val response = try {
            client.get("$root/search") {
                parameter("q", query)
                parameter("format", "json")
                parameter("language", language.ifBlank { "all" })
                parameter("safesearch", 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("SearXNG request failed: ${e::class.simpleName}: ${e.message}")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val why = if (response.status.value == 403) "the instance doesn't allow JSON output (add json to search.formats in its settings.yml)"
            else text.take(200)
            throw IllegalStateException("SearXNG failed: HTTP ${response.status.value}: $why")
        }
        val json = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("SearXNG didn't answer in JSON; is this the instance's address?")
        }
        return parse(json).take(maxResults)
    }

    companion object {
        fun parse(root: JsonObject): List<SearchResult> =
            root.objects("results").mapNotNull { SearchResult.of(it.str("title"), it.str("url"), it.str("content")) }
                // Engines often return the same page; keep the first (best-ranked).
                .distinctBy { it.url.trimEnd('/') }
    }
}
