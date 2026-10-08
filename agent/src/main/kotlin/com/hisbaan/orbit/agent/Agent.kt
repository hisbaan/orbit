package com.hisbaan.orbit.agent

import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ChatImage
import com.hisbaan.orbit.providers.ChatMessage
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class AgentResult(
    val reply: String,
    val afterTurn: List<AfterTurnAction>,
    val toolCalls: List<ToolCall>,
)

/**
 * The conversation loop: sends the user's words to the model, runs the tools it asks for,
 * feeds results back, and repeats until the model answers in text. [Tool.confirms] tools get
 * a [CONFIRMATION_ARG] the model fills with what to say if the action works. A step whose
 * tools are all [ToolOutcome.done] and that carries a confirmation ends the turn without
 * another model call; an empty confirmation means the model has more calls to make.
 * Text is streamed to `onText` as it arrives; a tool step's text that doesn't end the turn
 * is withdrawn with `onDiscardText`.
 *
 * History survives for [historyTtlMs] after a turn so a quick follow-up continues the
 * conversation; after that the next turn starts fresh.
 *
 * Tools run on [toolDispatcher], since many block (content providers, the package manager,
 * image encoding); the callbacks run on the caller's.
 *
 * Actions that need the user's yes come back from tools as [PendingAction]s. They are held
 * here under a short ref and run only through [CONFIRM_TOOL], with that ref, in the user's
 * next message: neither the model nor text it reads can confirm on the user's behalf.
 */
class Agent(
    tools: List<Tool>,
    private val systemPrompt: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyTtlMs: Long = 2 * 60_000,
    private val maxSteps: Int = 6,
    private val toolDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val history = mutableListOf<ChatMessage>()
    private var lastTurnEndedAt = 0L

    /** User messages so far in this conversation; a held action can be confirmed only in the next one. */
    private var userMessages = 0
    private var lastRef = 0
    private val held = mutableMapOf<String, Held>()

    private class Held(val userMessage: Int, val action: PendingAction)

    /**
     * Runs a held action once the user has agreed. Always offered, even with nothing held, so
     * the tool list (part of the prompt providers cache) doesn't change from request to request.
     */
    private val confirmAction = object : Tool {
        override val confirms = true

        override val spec = ToolSpec(
            name = CONFIRM_TOOL,
            description = "Carry out an action a tool held back for the user's yes, once the user plainly agrees to " +
                "the question you asked. If they change anything (\"yes, but his work number\"), call the original " +
                "tool again instead, which asks anew. If they decline, don't call this.",
            parameters = objectSchema(listOf("ref"), "ref" to stringProperty("The ref the tool's result gave, e.g. 'c1'")),
        )

        override suspend fun invoke(args: JsonObject): ToolOutcome {
            val ref = args.requireString("ref")
            val waiting = held[ref] ?: return ToolOutcome("Nothing is waiting under ref '$ref'. Call the original tool again.")
            if (waiting.userMessage == userMessages) {
                // Asked and "confirmed" in one message: the user hasn't answered yet.
                return ToolOutcome("Not done: the user hasn't answered yet. Ask them, and confirm only once they agree.")
            }
            held -= ref
            EventLog.log("agent", "Confirmed by the user: ${EventLog.content(waiting.action.description)}")
            return waiting.action.run()
        }
    }

    private val tools = tools + confirmAction
    private val toolsByName = this.tools.associateBy { it.spec.name }

    suspend fun respond(
        userText: String,
        transport: ChatTransport,
        model: String,
        onToolCall: (ToolCall) -> Unit = {},
        onText: (String) -> Unit = {},
        onDiscardText: () -> Unit = {},
        onCard: (Card) -> Unit = {},
    ): AgentResult {
        if (history.isNotEmpty() && clock() - lastTurnEndedAt > historyTtlMs) {
            EventLog.log("agent", "History expired; starting a new conversation")
            history.clear()
            held.clear()
        }
        userMessages++
        // Only the previous message's questions can be answered by this one.
        held.values.removeAll { it.userMessage < userMessages - 1 }
        val mark = history.size
        val afterTurn = mutableListOf<AfterTurnAction>()
        val calls = mutableListOf<ToolCall>()
        history += ChatMessage.User(userText)
        try {
            repeat(maxSteps) { step ->
                val request = ChatRequest(
                    model = model,
                    messages = listOf(ChatMessage.System(systemPrompt())) + history,
                    tools = tools.filter { it.available }.map(::specFor),
                )
                val response = transport.complete(request, onText)
                EventLog.log(
                    "agent",
                    "Step ${step + 1}: finish=${response.finishReason} text=${response.text.length} chars " +
                        "tools=${response.toolCalls.joinToString { it.name }.ifEmpty { "none" }}",
                )
                if (response.toolCalls.isEmpty()) {
                    history += ChatMessage.Assistant(response.text)
                    return AgentResult(response.text.trim(), afterTurn, calls)
                }
                history += ChatMessage.Assistant(response.text.ifBlank { null }, response.toolCalls)
                var allDone = true
                val written = mutableListOf<String>()
                val images = mutableListOf<Pair<String, List<ChatImage>>>()
                for (call in response.toolCalls) {
                    onToolCall(call)
                    calls += call
                    val (outcome, modelSays) = runTool(call)
                    outcome.afterTurn?.let(afterTurn::add)
                    outcome.cards.forEach(onCard)
                    allDone = allDone && outcome.done
                    if (outcome.images.isNotEmpty()) images += call.name to outcome.images
                    modelSays?.let(written::add)
                    history += ChatMessage.ToolResult(call.id, outcome.result)
                }
                if (images.isNotEmpty()) {
                    history += ChatMessage.User("Images from ${images.joinToString { it.first }}:", images.flatMap { it.second })
                }
                // Every action went through and the model said what to say: end the turn now
                // instead of asking the model again, saving a round trip. Text it wrote alongside
                // the calls wins over the confirmation arguments.
                if (allDone && (written.isNotEmpty() || response.text.isNotBlank())) {
                    val reply = response.text.trim().ifEmpty { written.joinToString(" ").also(onText) }
                    // Text the model wrote is already in history with its calls; a reply made of
                    // confirmations isn't, and a follow-up needs to know what was said.
                    if (response.text.isBlank()) history += ChatMessage.Assistant(reply)
                    EventLog.log("agent", "Step ${step + 1}: actions confirmed; no follow-up call")
                    return AgentResult(reply, afterTurn, calls)
                }
                // A failure or a lookup: text written alongside the calls is withdrawn and the
                // next step answers with the results in hand.
                if (response.text.isNotBlank()) onDiscardText()
            }
            EventLog.log("agent", "Gave up after $maxSteps steps")
            val reply = "Sorry, I couldn't finish that."
            history += ChatMessage.Assistant(reply)
            return AgentResult(reply, afterTurn, calls)
        } catch (e: Throwable) {
            // Leave history as it was before this turn so a retry starts clean. Its questions
            // were never heard, and the retry answers the previous message's.
            while (history.size > mark) history.removeAt(history.lastIndex)
            held.values.removeAll { it.userMessage == userMessages }
            userMessages--
            throw e
        } finally {
            lastTurnEndedAt = clock()
        }
    }

    fun reset() {
        history.clear()
        held.clear()
    }

    /** The tool's spec, with [CONFIRMATION_ARG] added (and required) for [Tool.confirms] tools. */
    private fun specFor(tool: Tool): ToolSpec {
        val spec = tool.spec
        if (!tool.confirms) return spec
        val params = spec.parameters
        val properties = JsonObject((params["properties"] as? JsonObject).orEmpty() + (CONFIRMATION_ARG to CONFIRMATION_SCHEMA))
        val required = JsonArray((params["required"] as? JsonArray).orEmpty() + JsonPrimitive(CONFIRMATION_ARG))
        return spec.copy(parameters = JsonObject(params + ("properties" to properties) + ("required" to required)))
    }

    /** Runs [call]; also returns the [CONFIRMATION_ARG] the model wrote, which the tool never sees. */
    private suspend fun runTool(call: ToolCall): Pair<ToolOutcome, String?> {
        val tool = toolsByName[call.name] ?: return ToolOutcome("Error: unknown tool '${call.name}'") to null
        val parsed = try {
            if (call.argumentsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome("Error: arguments were not valid JSON") to null
        }
        val modelSays = (parsed[CONFIRMATION_ARG] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val outcome = runTool(call, tool.privateResult) { tool.invoke(JsonObject(parsed - CONFIRMATION_ARG)) }
        return hold(call, outcome) to modelSays
    }

    /** Holds back the action [outcome] waits on, if any, and tells the model how to confirm it. */
    private fun hold(call: ToolCall, outcome: ToolOutcome): ToolOutcome {
        val action = outcome.pending ?: return outcome
        val ref = "c${++lastRef}"
        held[ref] = Held(userMessages, action)
        EventLog.log("agent", "Waiting for the user's yes ($ref): ${EventLog.content(action.description)}")
        return outcome.copy(
            result = outcome.result + "\nOnce the user agrees, call $CONFIRM_TOOL with ref '$ref'. If they change " +
                "anything, call ${call.name} again instead.",
            done = false,
        )
    }

    private suspend fun runTool(call: ToolCall, privateResult: Boolean = false, block: suspend () -> ToolOutcome): ToolOutcome {
        val args = EventLog.content(call.argumentsJson)
        return try {
            withContext(toolDispatcher) { block() }.also {
                val result = if (privateResult) "<${it.result.length} chars>" else EventLog.content(it.result)
                val after = it.afterTurn?.let { a -> " [after turn: ${EventLog.content(a.description)}]" }.orEmpty()
                EventLog.log("tool", "${call.name}($args) -> $result$after")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventLog.log("tool", "${call.name}($args) failed: ${e::class.simpleName}: ${EventLog.content(e.message)}")
            ToolOutcome("Error: ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        /** The argument in which the model writes what to say if a [Tool.confirms] tool works. */
        const val CONFIRMATION_ARG = "confirmation"

        /** The tool through which the model passes on the user's yes to a [PendingAction]. */
        const val CONFIRM_TOOL = "confirm_action"

        private val CONFIRMATION_SCHEMA = stringProperty(
            "What to tell the user if this works: one short present-tense sentence, e.g. \"Starting navigation to " +
                "the airport.\" Said only if it goes through, and then your turn ends. Leave it empty if you will " +
                "make more calls after seeing this one's result (e.g. the next step of the request). When calling " +
                "several tools at once, write one sentence covering all of them on the first call and leave this " +
                "empty on the others.",
        )
    }

}
