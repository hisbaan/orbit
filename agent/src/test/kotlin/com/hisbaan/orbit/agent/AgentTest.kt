package com.hisbaan.orbit.agent

import com.hisbaan.orbit.providers.ChatImage
import com.hisbaan.orbit.providers.ChatMessage
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatResponse
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private class RecordingTool(
        name: String,
        override val confirms: Boolean = false,
        private val outcome: (JsonObject) -> ToolOutcome,
    ) : Tool {
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

    private fun navigateTool() = RecordingTool("navigate", confirms = true) {
        ToolOutcome("Queued.", AfterTurnAction("navigate", needsUnlock = true) {}, done = true)
    }

    @Test
    fun `a confirmed action ends the turn without a second call`() = runTest {
        val transport = FakeTransport(calls(ToolCall("1", "navigate", """{"destination":"home","confirmation":"Heading home."}""")))
        val nav = navigateTool()
        val agent = Agent(listOf(nav, RecordingTool("get_weather") { ToolOutcome("sunny") }), systemPrompt = { "sys" })
        val streamed = StringBuilder()

        val result = agent.respond("take me home", transport, "m", onText = { streamed.append(it) })

        assertEquals(1, transport.requests.size)
        assertEquals("Heading home.", result.reply)
        assertEquals("Heading home.", streamed.toString())
        assertEquals(listOf("navigate"), result.afterTurn.map { it.description })
        // The tool never sees the confirmation; only confirming tools are asked for one.
        assertEquals(listOf(JsonObject(mapOf("destination" to JsonPrimitive("home")))), nav.calls)
        val specs = transport.requests[0].tools.associateBy { it.name }
        assertTrue("confirmation" in specs.getValue("navigate").parameters["required"].toString())
        assertTrue("confirmation" !in specs.getValue("get_weather").parameters.toString())
    }

    @Test
    fun `parallel calls share one confirmation`() = runTest {
        val media = RecordingTool("media_control", confirms = true) { ToolOutcome("Paused.", done = true) }
        val transport = FakeTransport(
            calls(
                ToolCall("1", "media_control", """{"action":"pause","confirmation":""}"""),
                ToolCall("2", "navigate", """{"destination":"home","confirmation":"Pausing the music and heading home."}"""),
            ),
        )
        val agent = Agent(listOf(media, navigateTool()), systemPrompt = { "sys" })

        assertEquals("Pausing the music and heading home.", agent.respond("pause and take me home", transport, "m").reply)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `an empty confirmation means the model has more to do`() = runTest {
        val transport = FakeTransport(
            calls(ToolCall("1", "navigate", """{"destination":"home","confirmation":""}""")),
            text("Heading home, then to the shop."),
        )
        val agent = Agent(listOf(navigateTool()), systemPrompt = { "sys" })

        assertEquals("Heading home, then to the shop.", agent.respond("home then the shop", transport, "m").reply)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `text written alongside the calls wins`() = runTest {
        val transport = FakeTransport(
            ChatResponse("On our way.", listOf(ToolCall("1", "navigate", """{"confirmation":"Heading home."}""")), "tool_calls"),
        )
        val agent = Agent(listOf(navigateTool()), systemPrompt = { "sys" })

        assertEquals("On our way.", agent.respond("take me home", transport, "m").reply)
    }

    @Test
    fun `withdraws the model's text when a tool doesn't go through`() = runTest {
        val open = RecordingTool("open_app") { ToolOutcome("No installed app is called 'Blue Bubbles'.") }
        val transport = FakeTransport(
            ChatResponse("Opening Blue Bubbles.", listOf(ToolCall("1", "open_app", "{}")), "tool_calls"),
            text("I couldn't find Blue Bubbles."),
        )
        val agent = Agent(listOf(open), systemPrompt = { "sys" })
        val events = mutableListOf<String>()

        val result = agent.respond("open blue bubbles", transport, "m", onText = { events += it }, onDiscardText = { events += "<discard>" })

        assertEquals(2, transport.requests.size)
        assertEquals(listOf("Opening Blue Bubbles.", "<discard>", "I couldn't find Blue Bubbles."), events)
        assertEquals("I couldn't find Blue Bubbles.", result.reply)
    }

    @Test
    fun `lookups without text still get a follow-up call`() = runTest {
        val weather = RecordingTool("get_weather") { ToolOutcome("14°C, cloudy") }
        val transport = FakeTransport(calls(ToolCall("1", "get_weather", "{}")), text("It's 14 degrees and cloudy."))
        val agent = Agent(listOf(weather), systemPrompt = { "sys" })

        assertEquals("It's 14 degrees and cloudy.", agent.respond("weather?", transport, "m").reply)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `passes tool images to the model after the results`() = runTest {
        val shot = ChatImage("image/jpeg", "QUJD")
        val screen = RecordingTool("read_screen") { ToolOutcome("Screen text: Dinner Friday 7pm", images = listOf(shot)) }
        val transport = FakeTransport(calls(ToolCall("1", "read_screen", "{}")), text("Dinner on Friday at 7."))
        val agent = Agent(listOf(screen), systemPrompt = { "sys" })

        agent.respond("what's on my screen", transport, "m")

        val messages = transport.requests[1].messages
        assertTrue(messages[messages.size - 2] is ChatMessage.ToolResult)
        assertEquals(ChatMessage.User("Images from read_screen:", listOf(shot)), messages.last())
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
