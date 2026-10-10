package com.hisbaan.orbit.websearch

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Tavily (tavily.com), a search API made for assistants: each result comes with the relevant
 * passage of the page, so the model can answer without Orbit fetching pages itself. Needs a key
 * (free for 1,000 searches a month).
 */
class Tavily(private val client: HttpClient, private val apiKey: String) : SearchProvider {
    override val name = "Tavily"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> {
        val body = buildJsonObject {
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
            // Tavily's own written answer would be one more unattributed layer; the model answers.
            put("include_answer", false)
        }
        val response = try {
            client.post("https://api.tavily.com/search") {
                header("Authorization", "Bearer $apiKey")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Tavily request failed: ${e::class.simpleName}: ${e.message.orEmpty().replace(apiKey, "<key>")}")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val why = when (response.status.value) {
                401 -> "the API key was rejected"
                429, 432, 433 -> "the plan's search limit was reached"
                else -> text.take(200).replace(apiKey, "<key>")
            }
            throw IllegalStateException("Tavily failed: HTTP ${response.status.value}: $why")
        }
        return parse(Json.parseToJsonElement(text).jsonObject).take(maxResults)
    }

    companion object {
        fun parse(root: JsonObject): List<SearchResult> =
            root.objects("results").mapNotNull { SearchResult.of(it.str("title"), it.str("url"), it.str("content")) }
    }
}
