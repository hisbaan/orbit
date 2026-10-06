package com.hisbaan.orbit.agent

import com.hisbaan.orbit.providers.ChatMessage
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatResponse
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    /** Replays canned responses and records every request. */
    private class FakeTransport(vararg responses: ChatResponse) : ChatTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<ChatRequest>()
        override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse {
            requests += request
            return queue.removeFirst().also { if (it.text.isNotEmpty()) onTextDelta(it.text) }
        }
        override suspend fun listModels() = emptyList<String>()
    }

    private class RecordingTool(name: String, private val outcome: (JsonObject) -> ToolOutcome) : Tool {
        val calls = mutableListOf<JsonObject>()
        override val spec = ToolSpec(name, "test tool", objectSchema())
        override suspend fun invoke(args: JsonObject): ToolOutcome {
            calls += args
            return outcome(args)
        }
    }

    private fun text(t: String) = ChatResponse(t, emptyList(), "stop")
    private fun calls(vararg c: ToolCall) = ChatResponse("", c.toList(), "tool_calls")

    @Test
    fun `runs tools, feeds results back and collects after-turn actions`() = runTest {
        val media = RecordingTool("media_control") { ToolOutcome("Paused.") }
        val nav = RecordingTool("navigate") { ToolOutcome("Queued.", AfterTurnAction("navigate", needsUnlock = true) {}) }
        val transport = FakeTransport(
            calls(ToolCall("1", "media_control", """{"action":"pause"}"""), ToolCall("2", "navigate", """{"destination":"home"}""")),
            text("Paused, and heading home."),
        )
        val agent = Agent(listOf(media, nav), systemPrompt = { "sys" })

        val result = agent.respond("pause and take me home", transport, "m")

        assertEquals("Paused, and heading home.", result.reply)
        assertEquals(listOf("navigate"), result.afterTurn.map { it.description })
        assertEquals(1, media.calls.size)
        val second = transport.requests[1].messages
        assertTrue(second.first() is ChatMessage.System)
        assertEquals(listOf("1", "2"), second.filterIsInstance<ChatMessage.ToolResult>().map { it.toolCallId })
        assertEquals("Paused.", second.filterIsInstance<ChatMessage.ToolResult>()[0].content)
    }

    @Test
    fun `streams text from every step, separated`() = runTest {
        val transport = FakeTransport(
            ChatResponse("Sure.", listOf(ToolCall("1", "media_control", "{}")), "tool_calls"),
            text("Paused."),
        )
        val agent = Agent(listOf(RecordingTool("media_control") { ToolOutcome("ok") }), systemPrompt = { "sys" })
        val streamed = StringBuilder()

        val result = agent.respond("pause", transport, "m", onText = { streamed.append(it) })

        assertEquals("Sure.\nPaused.", streamed.toString())
        assertEquals("Paused.", result.reply)
    }

    @Test
    fun `reports unknown tools and bad arguments to the model`() = runTest {
        val transport = FakeTransport(
            calls(ToolCall("1", "nope", "{}"), ToolCall("2", "media_control", "{not json")),
            text("Sorry."),
        )
        val agent = Agent(listOf(RecordingTool("media_control") { ToolOutcome("ok") }), systemPrompt = { "sys" })

        agent.respond("x", transport, "m")

        val results = transport.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().map { it.content }
        assertEquals(listOf("Error: unknown tool 'nope'", "Error: arguments were not valid JSON"), results)
    }

    @Test
    fun `keeps history within the TTL and drops it after`() = runTest {
        var now = 0L
        val agent = Agent(emptyList(), systemPrompt = { "sys" }, clock = { now }, historyTtlMs = 1_000)
        val transport = FakeTransport(text("a"), text("b"), text("c"))

        agent.respond("one", transport, "m")
        now = 500
        agent.respond("two", transport, "m")
        assertEquals(listOf("one", "two"), transport.requests[1].messages.filterIsInstance<ChatMessage.User>().map { it.content })
        now = 5_000
        agent.respond("three", transport, "m")
        assertEquals(listOf("three"), transport.requests[2].messages.filterIsInstance<ChatMessage.User>().map { it.content })
    }

    @Test
    fun `rolls back history when the transport fails`() = runTest {
        val agent = Agent(emptyList(), systemPrompt = { "sys" })
        val failing = object : ChatTransport {
            override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse = error("offline")
            override suspend fun listModels() = emptyList<String>()
        }
        runCatching { agent.respond("lost", failing, "m") }
        val transport = FakeTransport(text("ok"))
        agent.respond("retry", transport, "m")
        assertEquals(listOf("retry"), transport.requests[0].messages.filterIsInstance<ChatMessage.User>().map { it.content })
    }
}
