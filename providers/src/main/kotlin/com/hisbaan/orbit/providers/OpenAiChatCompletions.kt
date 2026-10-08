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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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
        return root.array("data").mapNotNull { (it as? JsonObject)?.string("id") }.sorted()
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
            chunk.obj("error")?.let { throw ProviderException(errorMessage(it)) }
            val choice = chunk.array("choices").firstOrNull() as? JsonObject ?: continue
            choice.string("finish_reason")?.let { finishReason = it }
            val delta = choice.obj("delta") ?: continue
            delta.string("content")?.let {
                text.append(it)
                onTextDelta(it)
            }
            delta.array("tool_calls").filterIsInstance<JsonObject>().forEach { call ->
                val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val partial = calls.getOrPut(index) { PartialCall() }
                call.string("id")?.let { partial.id = it }
                call.obj("function")?.let { fn ->
                    fn.string("name")?.let { partial.name.append(it) }
                    fn.string("arguments")?.let { partial.arguments.append(it) }
                }
            }
        }
        return ChatResponse(
            text = text.toString(),
            // An entry that never got a name isn't a call (some servers send empty placeholders).
            toolCalls = calls.entries.filter { it.value.name.isNotEmpty() }.map { (index, p) ->
                ToolCall(id = p.id ?: "call_$index", name = p.name.toString(), argumentsJson = p.arguments.toString())
            },
            finishReason = finishReason,
        )
    }

    private fun decodeFull(root: JsonObject, onTextDelta: (String) -> Unit): ChatResponse {
        val choice = root.array("choices").firstOrNull() as? JsonObject
            ?: throw ProviderException("Response has no choices")
        val message = choice.obj("message")
        val text = message?.string("content").orEmpty()
        if (text.isNotEmpty()) onTextDelta(text)
        val calls = message?.array("tool_calls").orEmpty().filterIsInstance<JsonObject>().mapIndexed { index, call ->
            val fn = call.obj("function")
            ToolCall(
                id = call.string("id") ?: "call_$index",
                name = fn?.string("name").orEmpty(),
                argumentsJson = fn?.string("arguments").orEmpty(),
            )
        }
        return ChatResponse(text, calls, choice.string("finish_reason"))
    }

    private suspend fun errorFrom(response: HttpResponse): ProviderException {
        val body = response.bodyAsText()
        val message = runCatching {
            errorMessage(json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject)
        }.getOrNull() ?: body.take(300)
        return ProviderException("HTTP ${response.status.value}: $message", response.status.value)
    }

    private fun errorMessage(error: JsonObject): String = error.string("message") ?: error.toString()

    private class PartialCall {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Field readers that treat JSON null (which some compatible servers send, e.g.
        // "tool_calls": null) or a value of another type as absent, instead of throwing.
        private fun JsonObject.obj(key: String) = get(key) as? JsonObject
        private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

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
                if (message.images.isEmpty()) {
                    put("content", message.content)
                } else {
                    // Content parts: the text, then each image as a data URL.
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", message.content)
                                },
                            ) + message.images.map { image ->
                                buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:${image.mimeType};base64,${image.base64}") })
                                }
                            },
                        ),
                    )
                }
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
