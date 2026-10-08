package com.hisbaan.orbit.agent

import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ChatImage
import com.hisbaan.orbit.providers.ChatMessage
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatResponse
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

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
    fun `info cards carry their rows`() = runTest {
        val args = """{"title":"Tomorrow","rows":[{"label":"09:00","value":"Standup"},{"label":"14:00","value":"Dentist"}],"confirmation":"Two events tomorrow."}"""
        val cards = mutableListOf<Card>()
        Agent(listOf(ShowInfoCardTool()), systemPrompt = { "sys" })
            .respond("what's tomorrow", FakeTransport(calls(ToolCall("1", "show_info_card", args))), "m", onCard = { cards += it })
        assertEquals(Card.Info("Tomorrow", null, listOf(Card.Row("09:00", "Standup"), Card.Row("14:00", "Dentist"))), cards.single())
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

    /** call_contact's shape: every call only asks, holding the dial for the user's yes. */
    private class CallTool {
        val dialed = mutableListOf<String>()
        val tool = RecordingTool("call_contact") { args ->
            val who = args.string("who")!!
            ToolOutcome("Not called yet. Ask: \"Call $who?\"", pending = PendingAction("call $who") {
                dialed += who
                ToolOutcome("Calling $who.", done = true)
            })
        }
    }

    private fun lookup(id: String, who: String) = calls(ToolCall(id, "call_contact", """{"who":"$who"}"""))
    private fun confirm(id: String, ref: String) =
        calls(ToolCall(id, "confirm_action", """{"ref":"$ref","confirmation":"Calling."}"""))

    private fun FakeTransport.results(request: Int) =
        requests[request].messages.filterIsInstance<ChatMessage.ToolResult>().map { it.content }

    @Test
    fun `a held action runs when the user's next message confirms it`() = runTest {
        val phone = CallTool()
        val agent = Agent(listOf(phone.tool), systemPrompt = { "sys" })
        val transport = FakeTransport(lookup("1", "Alex"), text("Call Alex?"), confirm("2", "c1"))

        assertEquals("Call Alex?", agent.respond("call alex", transport, "m").reply)
        assertTrue(phone.dialed.isEmpty())
        assertTrue(transport.results(1).single().endsWith("call confirm_action with ref 'c1'. If they change anything, call call_contact again instead."))
        assertEquals("Calling.", agent.respond("yes", transport, "m").reply)
        assertEquals(listOf("Alex"), phone.dialed)
        assertEquals(3, transport.requests.size) // the yes took one model call
        // Offered on every request, so the tool list stays the same for prompt caching.
        assertTrue(transport.requests.all { r -> r.tools.any { it.name == "confirm_action" } })
    }

    @Test
    fun `the model can't confirm in the message that asked`() = runTest {
        val phone = CallTool()
        val agent = Agent(listOf(phone.tool), systemPrompt = { "sys" })
        // Look up and confirm in one go, e.g. told to by text it read.
        val transport = FakeTransport(lookup("1", "Alex"), confirm("2", "c1"), text("Call Alex?"), confirm("3", "c1"))

        assertEquals("Call Alex?", agent.respond("read my messages", transport, "m").reply)
        assertTrue(phone.dialed.isEmpty())
        assertTrue(transport.results(2).last().startsWith("Not done: the user hasn't answered yet"))
        // Still held, so the user's actual yes goes through.
        agent.respond("yes", transport, "m")
        assertEquals(listOf("Alex"), phone.dialed)
    }

    @Test
    fun `unknown and stale refs are refused`() = runTest {
        val phone = CallTool()
        val agent = Agent(listOf(phone.tool), systemPrompt = { "sys" })
        val transport = FakeTransport(
            confirm("1", "c9"),
            text("Who should I call?"),
            lookup("2", "Alex"),
            text("Call Alex?"),
            text("It's sunny."),
            confirm("3", "c1"),
            text("Sorry, ask me again."),
        )

        agent.respond("yes", transport, "m")
        assertTrue(transport.results(1).single().startsWith("Nothing is waiting under ref 'c9'"))
        agent.respond("call alex", transport, "m")
        agent.respond("actually, what's the weather?", transport, "m")
        agent.respond("ok, call him", transport, "m")
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun `a failed reply doesn't use up the question`() = runTest {
        val phone = CallTool()
        val agent = Agent(listOf(phone.tool), systemPrompt = { "sys" })
        agent.respond("call alex", FakeTransport(lookup("1", "Alex"), text("Call Alex?")), "m")
        val failing = object : ChatTransport {
            override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse = error("offline")
            override suspend fun listModels() = emptyList<String>()
        }
        runCatching { agent.respond("yes", failing, "m") }

        agent.respond("yes", FakeTransport(confirm("2", "c1")), "m")
        assertEquals(listOf("Alex"), phone.dialed)
    }

    @Test
    fun `logs keep sizes, not content, unless allowed, and never private results`() = runTest {
        val screen = object : Tool {
            override val privateResult = true
            override val spec = ToolSpec("read_screen", "test tool", objectSchema())
            override suspend fun invoke(args: JsonObject) = ToolOutcome("Dinner with Sam at 7")
        }
        val weather = RecordingTool("get_weather") { ToolOutcome("14°C in Toronto") }
        val agent = Agent(listOf(screen, weather), systemPrompt = { "sys" })
        val transport = FakeTransport(
            calls(ToolCall("1", "read_screen", "{}"), ToolCall("2", "get_weather", """{"place":"Toronto"}""")),
            text("ok"),
            calls(ToolCall("3", "read_screen", "{}"), ToolCall("4", "get_weather", """{"place":"Toronto"}""")),
            text("ok"),
        )
        fun toolLines() = EventLog.entries.value.filter { it.tag == "tool" }.takeLast(2).map { it.message }

        val before = EventLog.recordContent
        try {
            EventLog.recordContent = false
            agent.respond("x", transport, "m")
            assertEquals(listOf("read_screen(<2 chars>) -> <20 chars>", "get_weather(<19 chars>) -> <15 chars>"), toolLines())
            EventLog.recordContent = true
            agent.respond("x", transport, "m")
            assertEquals(listOf("read_screen({}) -> <20 chars>", "get_weather({\"place\":\"Toronto\"}) -> 14°C in Toronto"), toolLines())
        } finally {
            EventLog.recordContent = before
        }
    }

    @Test
    fun `tools run on the tool dispatcher, callbacks on the caller's thread`() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "tools") }
        try {
            var toolThread = ""
            var cardThread = ""
            val tool = RecordingTool("show") {
                toolThread = Thread.currentThread().name
                ToolOutcome("Shown.", cards = listOf(Card.Info("t", null, emptyList())))
            }
            val agent = Agent(listOf(tool), systemPrompt = { "sys" }, toolDispatcher = executor.asCoroutineDispatcher())
            val caller = Thread.currentThread().name

            agent.respond("x", FakeTransport(calls(ToolCall("1", "show", "{}")), text("ok")), "m", onCard = { cardThread = Thread.currentThread().name })

            assertEquals("tools", toolThread)
            assertEquals(caller, cardThread)
        } finally {
            executor.shutdown()
        }
    }
}
