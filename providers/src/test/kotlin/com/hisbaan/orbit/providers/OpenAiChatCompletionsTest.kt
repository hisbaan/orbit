package com.hisbaan.orbit.providers

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsTest {
    private fun transport(status: HttpStatusCode = HttpStatusCode.OK, contentType: String, body: String, captured: MutableList<String>? = null) =
        OpenAiChatCompletions(
            baseUrl = "https://example.test/v1/",
            credential = ApiKeyCredential("sk-test"),
            client = HttpClient(
                MockEngine { request ->
                    assertEquals("https://example.test/v1/chat/completions", request.url.toString())
                    assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
                    captured?.add(request.body.toByteArray().decodeToString())
                    respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
                },
            ),
        )

    private val request = ChatRequest(model = "test-model", messages = listOf(ChatMessage.User("hi")))

    @Test
    fun `streams text deltas`() = runTest {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"lo."}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val deltas = mutableListOf<String>()
        val response = transport(contentType = "text/event-stream", body = sse).complete(request) { deltas += it }
        assertEquals("Hello.", response.text)
        assertEquals(listOf("Hel", "lo."), deltas)
        assertEquals("stop", response.finishReason)
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test
    fun `accumulates streamed tool calls by index`() = runTest {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"media_control","arguments":""}}]}}]}
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"action\":"}}]}}]}
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\":60}"}}]}}]}
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"pause\"}"}}]}}]}
            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent()
        val response = transport(contentType = "text/event-stream", body = sse).complete(request)
        assertEquals(
            listOf(
                ToolCall("call_a", "media_control", """{"action":"pause"}"""),
                ToolCall("call_b", "set_timer", """{"seconds":60}"""),
            ),
            response.toolCalls,
        )
        assertEquals("tool_calls", response.finishReason)
    }

    @Test
    fun `accepts non-streamed JSON responses`() = runTest {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Done."},"finish_reason":"stop"}]}"""
        val response = transport(contentType = "application/json", body = body).complete(request)
        assertEquals("Done.", response.text)
    }

    @Test
    fun `surfaces API error messages`() = runTest {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        val error = runCatching {
            transport(HttpStatusCode.Unauthorized, "application/json", body).complete(request)
        }.exceptionOrNull()
        assertTrue(error is ProviderException)
        assertEquals("HTTP 401: Incorrect API key provided", error!!.message)
    }

    @Test
    fun `sends reasoning_effort only when configured`() {
        assertEquals(null, OpenAiChatCompletions.encodeRequest(request)["reasoning_effort"])
        assertEquals("none", OpenAiChatCompletions.encodeRequest(request, "none")["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodes tool round trip messages`() = runTest {
        val captured = mutableListOf<String>()
        val conversation = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage.System("sys"),
                ChatMessage.User("pause"),
                ChatMessage.Assistant(null, listOf(ToolCall("c1", "media_control", """{"action":"pause"}"""))),
                ChatMessage.ToolResult("c1", "ok"),
            ),
        )
        transport(contentType = "text/event-stream", body = "data: [DONE]\n", captured = captured).complete(conversation)
        val messages = Json.parseToJsonElement(captured.single()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val toolCall = messages[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", toolCall["id"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }
}
