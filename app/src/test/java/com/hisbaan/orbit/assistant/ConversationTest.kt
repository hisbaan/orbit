package com.hisbaan.orbit.assistant

import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Agent
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatResponse
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    /** Hears what it's given, in order; records what it says. */
    private class FakeVoice(vararg heard: Heard) {
        private val queue = ArrayDeque(heard.toList())
        val listens = mutableListOf<Unit>()
        val said = mutableListOf<String>()

        /** Holds the first streamed reply after its first sentence, until it's interrupted. */
        var holdFirstReply = false
        private var replies = 0

        val voice = object : Voice {
            override suspend fun listen(onPartial: (String) -> Unit): Heard {
                listens += Unit
                return queue.removeFirstOrNull() ?: Heard.Silence
            }

            override suspend fun say(text: String) {
                said += text
            }

            override suspend fun sayAll(sentences: ReceiveChannel<String>) {
                val hold = holdFirstReply && replies++ == 0
                for (sentence in sentences) {
                    said += sentence
                    if (hold) CompletableDeferred<Unit>().await()
                }
            }
        }
    }

    /** Replays canned model responses. */
    private class FakeModel(vararg responses: ChatResponse) : ChatTransport {
        private val queue = ArrayDeque(responses.toList())
        override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse =
            queue.removeFirst().also { if (it.text.isNotEmpty()) onTextDelta(it.text) }

        override suspend fun listModels() = emptyList<String>()
    }

    private fun text(t: String) = ChatResponse(t, emptyList(), "stop")

    private val state = MutableStateFlow(AssistantState())
    private val log = mutableListOf<String>()
    private val startedWithReply = mutableListOf<String>()

    private fun TestScope.conversation(vararg tools: Tool): Conversation {
        // Tools run on the test's dispatcher, so the test controls when everything happens.
        val agent = Agent(tools.toList(), systemPrompt = { "sys" }, toolDispatcher = StandardTestDispatcher(testScheduler))
        return Conversation(agent, state) { actions -> startedWithReply += actions.map { it.description } }
    }

    private suspend fun Conversation.talk(voice: FakeVoice, model: FakeModel, typed: String? = null) =
        talk(voice.voice, model, "m", typed) { log += it }

    @Test
    fun `listens, answers and stops`() = runTest {
        val voice = FakeVoice(Heard.Text("what's the capital of France"))

        conversation().talk(voice, FakeModel(text("Paris.")))

        assertEquals(1, voice.listens.size)
        assertEquals(listOf("Paris."), voice.said)
        assertEquals("what's the capital of France", state.value.transcript)
        assertEquals("Paris.", state.value.reply)
    }

    @Test
    fun `a question listens for the answer, and silence then ends quietly`() = runTest {
        val voice = FakeVoice(Heard.Text("call Alex"))

        conversation().talk(voice, FakeModel(text("Alex Smith or Alex Jones?")))

        assertEquals(2, voice.listens.size)
        assertEquals(listOf("Alex Smith or Alex Jones?"), voice.said) // no "didn't catch that" after a question
    }

    @Test
    fun `silence on the first listen says so`() = runTest {
        val voice = FakeVoice(Heard.Silence)
        conversation().talk(voice, FakeModel())
        assertEquals(listOf("Sorry, I didn't catch that."), voice.said)
    }

    @Test
    fun `mic and recognizer failures are said and shown`() = runTest {
        val mic = FakeVoice(Heard.MicUnavailable)
        conversation().talk(mic, FakeModel())
        assertEquals(listOf("I couldn't open the microphone. Another app may be using it."), mic.said)
        assertEquals("Microphone unavailable", state.value.error)

        val stt = FakeVoice(Heard.Failed("On-device speech recognition isn't available on this phone."))
        conversation().talk(stt, FakeModel())
        assertEquals(listOf("On-device speech recognition isn't available on this phone."), stt.said)
    }

    @Test
    fun `a model failure is said, with the reason shown`() = runTest {
        val voice = FakeVoice(Heard.Text("hello"))
        val broken = object : ChatTransport {
            override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse = error("offline")
            override suspend fun listModels() = emptyList<String>()
        }

        conversation().talk(voice.voice, broken, "m", typed = null) { log += it }

        assertEquals(listOf("I couldn't reach the model."), voice.said)
        assertEquals("offline", state.value.error)
    }

    @Test
    fun `stops after the most exchanges`() = runTest {
        val voice = FakeVoice(*Array(10) { Heard.Text("hmm") })
        val model = FakeModel(*Array(10) { text("And then?") })

        conversation().talk(voice, model)

        assertEquals(Conversation.MAX_EXCHANGES, voice.listens.size)
    }

    @Test
    fun `typed text stands in for the first listen`() = runTest {
        val voice = FakeVoice()
        conversation().talk(voice, FakeModel(text("It's noon.")), typed = "what time is it")
        assertEquals(0, voice.listens.size)
        assertEquals(listOf("It's noon."), voice.said)
    }

    @Test
    fun `an interruption stops the reply and listens again`() = runTest {
        val voice = FakeVoice(Heard.Text("tell me a story"), Heard.Text("actually, never mind")).apply { holdFirstReply = true }
        val model = FakeModel(text("Once upon a time. There was a lamp."), text("Okay."))
        val conversation = conversation()
        assertFalse("nothing to interrupt yet", conversation.interrupt())

        val turn = launch { conversation.talk(voice, model) }
        advanceUntilIdle()
        assertTrue(conversation.interruptible)
        assertTrue(conversation.interrupt())
        turn.join()

        // The rest of the first reply went unsaid; the next listen heard the correction.
        assertEquals(listOf("Once upon a time.", "Okay."), voice.said)
        assertTrue(log.contains("Interrupted"))
        assertFalse(conversation.interruptible)
    }

    @Test
    fun `silent actions start with the reply, the rest wait for the turn to end`() = runTest {
        val tool = object : Tool {
            override val confirms = true
            override val spec = ToolSpec("navigate", "test", objectSchema())
            override suspend fun invoke(args: JsonObject) = ToolOutcome(
                "Queued.",
                afterTurn = AfterTurnAction("navigate", needsUnlock = true, duringReply = true) {},
                done = true,
            )
        }
        val music = object : Tool {
            override val confirms = true
            override val spec = ToolSpec("play_music", "test", objectSchema())
            override suspend fun invoke(args: JsonObject) =
                ToolOutcome("Queued.", afterTurn = AfterTurnAction("play music", needsUnlock = false) {}, done = true)
        }
        val model = FakeModel(
            ChatResponse(
                "",
                listOf(
                    ToolCall("1", "navigate", """{"confirmation":"Heading home with your music."}"""),
                    ToolCall("2", "play_music", """{"confirmation":""}"""),
                ),
                "tool_calls",
            ),
        )

        val later = conversation(tool, music).talk(FakeVoice(Heard.Text("take me home and play music")), model)

        assertEquals(listOf("navigate"), startedWithReply)
        assertEquals(listOf("play music"), later.map { it.description })
        assertEquals(listOf("navigate", "play_music"), state.value.actions)
    }

    @Test
    fun `typed questions are answered on screen only`() = runTest {
        val actions = conversation().answer("what's the capital of France", FakeModel(text("Paris.")), "m") { log += it }
        assertTrue(actions.isEmpty())
        assertEquals("Paris.", state.value.reply)
        assertFalse(state.value.phase == Phase.SPEAKING)
    }
}
