package com.hisbaan.orbit.agent

import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ChatMessage
import com.hisbaan.orbit.providers.ChatRequest
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class AgentResult(
    val reply: String,
    val afterTurn: List<AfterTurnAction>,
    val toolCalls: List<ToolCall>,
)

/**
 * The conversation loop: sends the user's words to the model, runs the tools it asks for,
 * feeds results back, and repeats until the model answers in text. Text is streamed to
 * `onText` as it arrives, from every step.
 *
 * History survives for [historyTtlMs] after a turn so a quick follow-up continues the
 * conversation; after that the next turn starts fresh.
 */
class Agent(
    private val tools: List<Tool>,
    private val systemPrompt: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyTtlMs: Long = 2 * 60_000,
    private val maxSteps: Int = 6,
) {
    private val history = mutableListOf<ChatMessage>()
    private var lastTurnEndedAt = 0L
    private val toolsByName = tools.associateBy { it.spec.name }

    suspend fun respond(
        userText: String,
        transport: ChatTransport,
        model: String,
        onToolCall: (ToolCall) -> Unit = {},
        onText: (String) -> Unit = {},
    ): AgentResult {
        if (history.isNotEmpty() && clock() - lastTurnEndedAt > historyTtlMs) {
            EventLog.log("agent", "History expired; starting a new conversation")
            history.clear()
        }
        val mark = history.size
        val afterTurn = mutableListOf<AfterTurnAction>()
        val calls = mutableListOf<ToolCall>()
        history += ChatMessage.User(userText)
        try {
            repeat(maxSteps) { step ->
                val request = ChatRequest(
                    model = model,
                    messages = listOf(ChatMessage.System(systemPrompt())) + history,
                    tools = tools.filter { it.available }.map { it.spec },
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
                // Text before a tool call ("Sure.") and the final reply are separate sentences.
                if (response.text.isNotBlank()) onText("\n")
                for (call in response.toolCalls) {
                    onToolCall(call)
                    calls += call
                    val outcome = runTool(call)
                    outcome.afterTurn?.let(afterTurn::add)
                    history += ChatMessage.ToolResult(call.id, outcome.result)
                }
            }
            EventLog.log("agent", "Gave up after $maxSteps steps")
            val reply = "Sorry, I couldn't finish that."
            history += ChatMessage.Assistant(reply)
            return AgentResult(reply, afterTurn, calls)
        } catch (e: Throwable) {
            // Leave history as it was before this turn so a retry starts clean.
            while (history.size > mark) history.removeAt(history.lastIndex)
            throw e
        } finally {
            lastTurnEndedAt = clock()
        }
    }

    fun reset() = history.clear()

    private suspend fun runTool(call: ToolCall): ToolOutcome {
        val tool = toolsByName[call.name] ?: return ToolOutcome("Error: unknown tool '${call.name}'")
        val args = try {
            if (call.argumentsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome("Error: arguments were not valid JSON")
        }
        return try {
            tool.invoke(args).also {
                EventLog.log("tool", "${call.name}(${call.argumentsJson}) -> ${it.result}${it.afterTurn?.let { a -> " [after turn: ${a.description}]" } ?: ""}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventLog.log("tool", "${call.name}(${call.argumentsJson}) failed: $e")
            ToolOutcome("Error: ${e.message ?: e::class.simpleName}")
        }
    }
}
