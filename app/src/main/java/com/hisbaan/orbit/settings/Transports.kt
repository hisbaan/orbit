package com.hisbaan.orbit.settings

import com.hisbaan.orbit.providers.ApiKeyCredential
import com.hisbaan.orbit.providers.ChatTransport
import com.hisbaan.orbit.providers.OpenAiChatCompletions
import io.ktor.client.HttpClient

/**
 * The model endpoint these settings describe. The one place a transport and credential are
 * chosen, so the Responses API and subscription sign-in (PLAN.md Phase 3) slot in here.
 */
fun AppSettings.chatTransport(client: HttpClient): ChatTransport =
    OpenAiChatCompletions(baseUrl, ApiKeyCredential(apiKey), client, reasoningEffort)
