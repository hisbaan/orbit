package com.hisbaan.orbit.settings

import com.hisbaan.orbit.providers.ApiKeyCredential
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.OpenAiChatCompletions
import io.ktor.client.HttpClient

/**
 * Whether [baseUrl] would send the API key unencrypted over the internet: `http` to anything but
 * this phone or a private network (a model server at home is fine).
 */
fun sendsKeyInTheClear(baseUrl: String): Boolean {
    val url = baseUrl.trim().lowercase()
    if (!url.startsWith("http://")) return false
    val host = url.removePrefix("http://").substringBefore('/').substringBefore('?').let {
        if (it.startsWith("[")) it.substringBefore(']').removePrefix("[") else it.substringBefore(':')
    }
    return !isLocalHost(host)
}

private fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host == "::1") return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return false
    val (a, b) = parts
    return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
}

/**
 * The model endpoint these settings describe. The one place a transport and credential are
 * chosen, so the Responses API and subscription sign-in (PLAN.md Phase 3) slot in here.
 */
fun AppSettings.chatTransport(client: HttpClient): ChatTransport =
    OpenAiChatCompletions(baseUrl, ApiKeyCredential(apiKey), client, reasoningEffort)
