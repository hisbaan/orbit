package com.hisbaan.orbit

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.service.media.MediaBrowserService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Runs a tool (including its after-turn action) without a voice turn, for testing from adb:
 *
 *   adb shell am broadcast -n com.hisbaan.orbit/.DebugToolReceiver \
 *       --es tool play_music --es args '{"query":"Eden"}'
 *
 * `--es tool playFromSearch --es query X [--es focus <mime>]` sends a raw playFromSearch to the
 * preferred music app's session, for probing what a player accepts.
 *
 * `--es tool say --es text "what's the capital of France"` runs a full voice turn with that
 * text in place of the first listen (route, model, streamed speech, follow-ups).
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as OrbitApp
        val name = intent.getStringExtra("tool") ?: return
        if (name == "voices") {
            // Lists the TTS engine's English voices: name, quality, latency, network, installed.
            lateinit var tts: android.speech.tts.TextToSpeech
            tts = android.speech.tts.TextToSpeech(app) {
                val voices = tts.voices.orEmpty().filter { it.locale.language == "en" }.sortedBy { it.name }
                EventLog.log("debug", "Default: ${tts.defaultVoice?.name}; ${voices.size} English voices")
                voices.forEach { v ->
                    val installed = android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
                    EventLog.log("debug", "${v.name} ${v.locale} q=${v.quality} lat=${v.latency} net=${v.isNetworkConnectionRequired} installed=$installed")
                }
                tts.shutdown()
            }
            return
        }
        if (name == "say") {
            app.assistant.trigger("debug", null, text = intent.getStringExtra("text").orEmpty())
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (name == "playFromSearch") {
                    rawPlayFromSearch(app, intent.getStringExtra("query").orEmpty(), intent.getStringExtra("focus"), intent.getStringExtra("pkg"))
                } else if (name == "browse") {
                    probeBrowser(app, intent.getStringExtra("pkg") ?: "com.google.android.apps.youtube.music")
                } else {
                    val tool = app.tools.firstOrNull { it.spec.name == name }
                        ?: return@launch EventLog.log("debug", "No tool '$name'")
                    val args = Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}").jsonObject
                    val outcome = tool.invoke(args)
                    EventLog.log("debug", "$name($args) -> ${outcome.result}")
                    outcome.afterTurn?.let {
                        it.run()
                        EventLog.log("debug", "Ran after-turn: ${it.description}")
                    }
                }
            } catch (e: Exception) {
                EventLog.log("debug", "$name failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    /** Connects to a player's MediaBrowserService and logs whether it lets Orbit browse. */
    private suspend fun probeBrowser(app: OrbitApp, pkg: String) {
        val service = app.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return EventLog.log("debug", "$pkg has no MediaBrowserService")
        val component = ComponentName(service.packageName, service.name)
        withContext(Dispatchers.Main) {
            val connected = CompletableDeferred<Boolean>()
            lateinit var browser: MediaBrowser
            browser = MediaBrowser(app, component, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { connected.complete(true) }
                override fun onConnectionFailed() { connected.complete(false) }
                override fun onConnectionSuspended() { connected.complete(false) }
            }, null)
            browser.connect()
            val ok = withTimeoutOrNull(5_000) { connected.await() }
            EventLog.log("debug", "browse $component: connected=$ok")
            if (ok == true) {
                val root = browser.root
                EventLog.log("debug", "root=$root")
                val children = CompletableDeferred<String>()
                browser.subscribe(root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<MediaBrowser.MediaItem>) {
                        children.complete(items.joinToString { "${it.description.title} [${it.mediaId}] ${if (it.isPlayable) "playable" else "browsable"}" })
                    }
                    override fun onError(parentId: String) { children.complete("error") }
                })
                EventLog.log("debug", "root children: ${withTimeoutOrNull(8_000) { children.await() }}")
            }
            browser.disconnect()
        }
    }

    private suspend fun rawPlayFromSearch(app: OrbitApp, query: String, focus: String?, target: String?) {
        val pkg = target ?: app.settings.current().musicPackage
        val controller = app.mediaSessions.controllers().firstOrNull { it.packageName == pkg } ?: app.mediaSessions.target(pkg)
            ?: return EventLog.log("debug", "No session for $pkg")
        val extras = Bundle().apply { focus?.let { putString(MediaStore.EXTRA_MEDIA_FOCUS, it) } }
        EventLog.log("debug", "raw playFromSearch('$query', focus=$focus) -> ${controller.packageName}")
        controller.transportControls.playFromSearch(query, extras)
    }
}
