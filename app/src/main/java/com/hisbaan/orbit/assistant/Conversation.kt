package com.hisbaan.orbit.assistant

import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Agent
import com.hisbaan.orbit.agent.SentenceChunker
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one listen heard. */
sealed interface Heard {
    data class Text(val text: String) : Heard

    /** Nothing was said before the recognizer gave up. */
    data object Silence : Heard

    data class Failed(val message: String) : Heard

    /** Orbit's own capture couldn't be opened, or stopped. */
    data object MicUnavailable : Heard
}

/** How a voice turn hears and speaks: [TurnAudio] on the phone, a fake in tests. */
interface Voice {
    /** Listens for one utterance, with the listening sounds around it. */
    suspend fun listen(onPartial: (String) -> Unit): Heard

    suspend fun say(text: String)

    /** Speaks each sentence as it arrives, until [sentences] is closed. */
    suspend fun sayAll(sentences: ReceiveChannel<String>)
}

/**
 * The talking part of a turn, apart from the audio plumbing: listen → think and speak, and
 * again without a button press when the reply asks a question (an answer, a confirmation) or
 * the user interrupts it. Replies are spoken sentence by sentence while they stream in.
 * [state] is what the UI shows. Plain Kotlin, so it runs in unit tests against fakes.
 */
class Conversation(
    private val agent: Agent,
    private val state: MutableStateFlow<AssistantState>,
    /** Starts actions that make no sound (opening an app) as the reply starts, while the turn goes on. */
    private val startDuringReply: (List<AfterTurnAction>) -> Unit,
) {
    /** True while a streamed reply is being spoken: a press then interrupts instead of cancelling. */
    @Volatile
    var interruptible = false
        private set

    private val interrupts = Channel<Unit>(Channel.CONFLATED)

    /** Stops the reply being spoken and listens again. False when nothing is being spoken. */
    fun interrupt(): Boolean {
        if (!interruptible) return false
        interrupts.trySend(Unit)
        return true
    }

    /**
     * A voice turn's exchanges, up to [MAX_EXCHANGES]. [typed] stands in for the first listen
     * (the debug hook). Returns the actions to run once the turn's audio is released.
     */
    suspend fun talk(voice: Voice, transport: ChatTransport, model: String, typed: String?, log: (String) -> Unit): List<AfterTurnAction> {
        val afterTurn = mutableListOf<AfterTurnAction>()
        exchanges(voice, transport, model, typed, afterTurn, log)
        return afterTurn
    }

    /**
     * Answers typed [text] on screen only: no speech, no follow-up listening (typing means
     * talking out loud isn't an option). Returns the actions to run after it.
     */
    suspend fun answer(text: String, transport: ChatTransport, model: String, log: (String) -> Unit): List<AfterTurnAction> {
        val afterTurn = mutableListOf<AfterTurnAction>()
        thinkAndSpeak(text, transport, model, voice = null, afterTurn, log)
        return afterTurn
    }

    /** Says [text] with [voice], or with none only shows it. */
    suspend fun say(voice: Voice?, text: String, error: String? = null) {
        if (voice == null) return state.update { it.copy(reply = text, error = error) }
        state.update { it.copy(phase = Phase.SPEAKING, reply = text, error = error) }
        voice.say(text)
    }

    private suspend fun exchanges(
        voice: Voice,
        transport: ChatTransport,
        model: String,
        typed: String?,
        afterTurn: MutableList<AfterTurnAction>,
        log: (String) -> Unit,
    ) {
        var followUp = false
        repeat(MAX_EXCHANGES) { exchange ->
            val text = if (exchange == 0 && typed != null) {
                typed
            } else {
                state.value = AssistantState(phase = Phase.LISTENING)
                log(if (followUp) "Listening for a follow-up" else "Listening")
                val heard = voice.listen(onPartial = { partial -> state.update { it.copy(partialTranscript = partial) } })
                log("Heard: ${if (heard is Heard.Text) EventLog.content(heard.text) else heard}")
                when (heard) {
                    is Heard.Text -> heard.text
                    // Silence after a question just ends the turn.
                    Heard.Silence -> return if (followUp) Unit else say(voice, "Sorry, I didn't catch that.")
                    Heard.MicUnavailable -> return say(voice, "I couldn't open the microphone. Another app may be using it.", error = "Microphone unavailable")
                    is Heard.Failed -> return say(voice, heard.message, error = heard.message)
                }
            }
            state.value = AssistantState(phase = Phase.THINKING, transcript = text)

            val reply = thinkAndSpeak(text, transport, model, voice, afterTurn, log) ?: return
            followUp = when {
                reply.interrupted -> true.also { log("Interrupted") }
                reply.text.trimEnd().endsWith('?') -> true.also { log("Reply asks a question: listening for the answer") }
                else -> return
            }
        }
    }

    private class Reply(val text: String, val interrupted: Boolean)

    /**
     * Runs the agent and speaks its reply sentence by sentence while it streams in. An
     * [interrupt] stops the speech; the agent still finishes, so its tool results and
     * after-turn actions aren't lost. With no [voice] the reply is only shown. Returns null if
     * the model couldn't be reached (already reported).
     */
    private suspend fun thinkAndSpeak(
        text: String,
        transport: ChatTransport,
        model: String,
        voice: Voice?,
        afterTurn: MutableList<AfterTurnAction>,
        log: (String) -> Unit,
    ): Reply? = coroutineScope {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val chunker = SentenceChunker()
        val shown = StringBuilder()
        var interrupted = false
        while (interrupts.tryReceive().isSuccess) Unit // drop presses from before this reply

        val speaking = launch { voice?.sayAll(sentences) }
        val watcher = launch {
            interrupts.receive()
            interrupted = true
            interruptible = false
            sentences.close()
            speaking.cancel()
        }
        fun enqueue(sentence: String) {
            if (interrupted || voice == null) return
            if (state.value.phase != Phase.SPEAKING) {
                log("First sentence ready")
                state.update { it.copy(phase = Phase.SPEAKING) }
                interruptible = true
            }
            sentences.trySend(sentence)
        }

        try {
            val result = try {
                agent.respond(
                    text,
                    transport,
                    model,
                    onToolCall = { call -> state.update { it.copy(actions = it.actions + call.name) } },
                    onText = { delta ->
                        shown.append(delta)
                        state.update { it.copy(reply = shown.toString().trim()) }
                        chunker.add(delta).forEach(::enqueue)
                    },
                    onCard = { card -> state.update { it.copy(cards = it.cards + card) } },
                    // A confirmation written alongside a tool call that didn't go through.
                    // A one-sentence confirmation is still in the chunker, so nothing was said.
                    onDiscardText = {
                        shown.setLength(0)
                        chunker.clear()
                        state.update { it.copy(reply = null) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Agent failed: $e")
                interruptible = false
                sentences.close()
                speaking.join()
                val reason = (e as? ProviderException)?.message ?: e.message ?: e::class.simpleName
                say(voice, "I couldn't reach the model.", error = reason)
                return@coroutineScope null
            }
            val (now, later) = result.afterTurn.partition { it.duringReply }
            afterTurn += later
            if (now.isNotEmpty()) {
                // Silent actions (opening an app, starting navigation) start with the reply.
                log("Starting with the reply: ${EventLog.content(now.joinToString { it.description })}")
                startDuringReply(now)
            }
            log("Reply: ${EventLog.content(result.reply)}")
            chunker.flush()?.let(::enqueue)
            if (shown.isBlank()) {
                val fallback = result.reply.ifBlank { "Done." }
                state.update { it.copy(reply = fallback) }
                enqueue(fallback)
            }
            sentences.close()
            speaking.join()
            Reply(result.reply, interrupted)
        } finally {
            interruptible = false
            watcher.cancel()
        }
    }

    companion object {
        /** Listen/speak exchanges in one turn before Orbit stops listening for follow-ups. */
        const val MAX_EXCHANGES = 5
    }
}
