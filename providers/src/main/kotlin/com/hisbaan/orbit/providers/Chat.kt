package com.hisbaan.orbit.providers

import kotlinx.serialization.json.JsonObject

/** Provider-neutral chat model. Transports translate it to their wire format. */
sealed interface ChatMessage {
    data class System(val content: String) : ChatMessage
    data class User(val content: String) : ChatMessage
    data class Assistant(val content: String?, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage
    data class ToolResult(val toolCallId: String, val content: String) : ChatMessage
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** A function the model may call. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
)

data class ChatResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
)

interface ChatTransport {
    /** Runs one completion. [onTextDelta] receives text as it streams in, if the transport streams. */
    suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit = {}): ChatResponse

    /** Model ids the endpoint offers, sorted. */
    suspend fun listModels(): List<String>
}

/** Supplies request authentication. Static API keys now; OAuth with refresh later. */
fun interface Credential {
    suspend fun headers(): Map<String, String>
}

class ApiKeyCredential(private val apiKey: String) : Credential {
    override suspend fun headers(): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
}

class ProviderException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
