package com.hisbaan.orbit.providers

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI-style `/chat/completions` with streaming and function calling. Works with OpenAI and
 * the many endpoints that copy its API. [baseUrl] is the API root, e.g.
 * `https://api.openai.com/v1`. [reasoningEffort] is sent as `reasoning_effort` when set; null
 * leaves it to the provider (some newer OpenAI models need "none" to allow tools here).
 */
class OpenAiChatCompletions(
    baseUrl: String,
    private val credential: Credential,
    private val client: HttpClient,
    private val reasoningEffort: String? = null,
) : ChatTransport {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun complete(request: ChatRequest, onTextDelta: (String) -> Unit): ChatResponse {
        val body = encodeRequest(request, reasoningEffort)
        return client.preparePost("$baseUrl/chat/completions") {
            credential.headers().forEach { (k, v) -> header(k, v) }
            header("Accept", "text/event-stream")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }.execute { response ->
            if (!response.status.isSuccess()) throw errorFrom(response)
            // Some compatible servers ignore `stream` and answer with plain JSON.
            if (response.contentType()?.match(ContentType.Application.Json) == true) {
                decodeFull(json.parseToJsonElement(response.bodyAsText()).jsonObject, onTextDelta)
            } else {
                decodeStream(response, onTextDelta)
            }
        }
    }

    override suspend fun listModels(): List<String> {
        val response = client.get("$baseUrl/models") {
            credential.headers().forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) throw errorFrom(response)
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return root["data"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .sorted()
    }

    private suspend fun decodeStream(response: HttpResponse, onTextDelta: (String) -> Unit): ChatResponse {
        val text = StringBuilder()
        val calls = sortedMapOf<Int, PartialCall>()
        var finishReason: String? = null
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val chunk = json.parseToJsonElement(data).jsonObject
            chunk["error"]?.let { throw ProviderException(errorMessage(it.jsonObject)) }
            val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            val delta = choice["delta"]?.jsonObject ?: continue
            delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                text.append(it)
                onTextDelta(it)
            }
            delta["tool_calls"]?.jsonArray?.forEach { element ->
                val call = element.jsonObject
                val index = call["index"]?.jsonPrimitive?.int ?: 0
                val partial = calls.getOrPut(index) { PartialCall() }
                call["id"]?.jsonPrimitive?.contentOrNull?.let { partial.id = it }
                call["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name.append(it) }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { partial.arguments.append(it) }
                }
            }
        }
        return ChatResponse(
            text = text.toString(),
            toolCalls = calls.entries.map { (index, p) ->
                ToolCall(id = p.id ?: "call_$index", name = p.name.toString(), argumentsJson = p.arguments.toString())
            },
            finishReason = finishReason,
        )
    }

    private fun decodeFull(root: JsonObject, onTextDelta: (String) -> Unit): ChatResponse {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderException("Response has no choices")
        val message = choice["message"]?.jsonObject
        val text = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isNotEmpty()) onTextDelta(text)
        val calls = message?.get("tool_calls")?.jsonArray.orEmpty().mapIndexed { index, element ->
            val call = element.jsonObject
            val fn = call["function"]?.jsonObject
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index",
                name = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                argumentsJson = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return ChatResponse(text, calls, choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
    }

    private suspend fun errorFrom(response: HttpResponse): ProviderException {
        val body = response.bodyAsText()
        val message = runCatching {
            errorMessage(json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject)
        }.getOrNull() ?: body.take(300)
        return ProviderException("HTTP ${response.status.value}: $message", response.status.value)
    }

    private fun errorMessage(error: JsonObject): String =
        error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()

    private class PartialCall {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun encodeRequest(request: ChatRequest, reasoningEffort: String? = null): JsonObject = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            reasoningEffort?.let { put("reasoning_effort", it) }
            put("messages", buildJsonArray { request.messages.forEach { add(encodeMessage(it)) } })
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.parameters)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

        private fun encodeMessage(message: ChatMessage): JsonObject = when (message) {
            is ChatMessage.System -> buildJsonObject {
                put("role", "system")
                put("content", message.content)
            }
            is ChatMessage.User -> buildJsonObject {
                put("role", "user")
                put("content", message.content)
            }
            is ChatMessage.Assistant -> buildJsonObject {
                put("role", "assistant")
                put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
                if (message.toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        JsonArray(
                            message.toolCalls.map { call ->
                                buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.argumentsJson)
                                        },
                                    )
                                }
                            },
                        ),
                    )
                }
            }
            is ChatMessage.ToolResult -> buildJsonObject {
                put("role", "tool")
                put("tool_call_id", message.toolCallId)
                put("content", message.content)
            }
        }
    }
}
