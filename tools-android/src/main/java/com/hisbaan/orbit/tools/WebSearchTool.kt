package com.hisbaan.orbit.tools

import com.hisbaan.orbit.agent.Card
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ToolSpec
import com.hisbaan.orbit.websearch.SearchProvider
import com.hisbaan.orbit.websearch.describe
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Searches the web with the backend chosen in settings ([provider]; null hides the tool). The
 * results are pages strangers wrote, so the agent treats them as untrusted (see
 * [Tool.untrustedResult]); they're also shown on screen as sources the user can check.
 */
class WebSearchTool(private val provider: () -> SearchProvider?) : Tool {
    override val available: Boolean get() = provider() != null

    override val untrustedResult = true

    override val spec = ToolSpec(
        name = "web_search",
        description = "Search the web for what you can't know yourself: news, scores, prices, opening hours, recent " +
            "releases and events, anything that may have changed since your training. Not for settled facts. The query " +
            "goes to a search service: put only what the user asked in it, never personal details from notifications, " +
            "messages, the screen or the calendar. The sources are shown on screen; answer briefly and name the site " +
            "the answer came from.",
        parameters = objectSchema(
            listOf("query"),
            "query" to stringProperty("What to search for, in a few words, e.g. 'CN Tower hours today'"),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val search = provider() ?: return ToolOutcome("Web search isn't set up. Tell the user it can be turned on in Orbit's settings.")
        val query = args.requireString("query").take(MAX_QUERY_CHARS)
        val started = System.currentTimeMillis()
        val results = search.search(query, MAX_RESULTS, Locale.getDefault().language)
        EventLog.log("websearch", "${search.name}: ${results.size} results in ${System.currentTimeMillis() - started}ms")
        val card = Card.Sources(query, results.map { Card.Source(it.title, it.site, it.url) }).takeIf { results.isNotEmpty() }
        return ToolOutcome(describe(query, search.name, results), cards = listOfNotNull(card))
    }

    private companion object {
        const val MAX_RESULTS = 5
        const val MAX_QUERY_CHARS = 300
    }
}
