package com.hisbaan.orbit

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.service.media.MediaBrowserService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Runs a tool (including its after-turn action) without a voice turn, for testing from adb:
 *
 *   adb shell am broadcast -n com.hisbaan.orbit/.DebugToolReceiver \
 *       --es tool play_music --es args '{"query":"Eden"}'
 *
 * For a tool that holds an action for the user's yes (call_contact, delete_calendar_event,
 * home_call_service), `"confirmed": true` in args runs it, standing in for confirm_action.
 *
 * `--es tool playFromSearch --es query X [--es focus <mime>]` sends a raw playFromSearch to the
 * preferred music app's session, for probing what a player accepts.
 *
 * `--es tool say --es text "what's the capital of France"` runs a full voice turn with that
 * text in place of the first listen (route, model, streamed speech, follow-ups).
 *
 * `--es tool stt --es text "set volume to 20 percent"` speaks the text with the phone's TTS into
 * a file and feeds it to the on-device recognizer as a listen would, logging every partial.
 * `--ei tail <ms>` is how long the audio runs on after the speech (default 4000), then the
 * stream closes; `--ei noise <dBFS>` lays white noise under all of it.
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as OrbitApp
        val name = intent.getStringExtra("tool") ?: return
        if (name == "voices") {
            // Lists the TTS engine's English voices: name, quality, latency, network, installed.
            // goAsync keeps the process alive until the engine has answered.
            val done = goAsync()
            lateinit var tts: android.speech.tts.TextToSpeech
            tts = android.speech.tts.TextToSpeech(app) {
                val voices = tts.voices.orEmpty().filter { it.locale.language == "en" }.sortedBy { it.name }
                EventLog.log("debug", "Default: ${tts.defaultVoice?.name}; ${voices.size} English voices")
                voices.forEach { v ->
                    val installed = android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
                    EventLog.log("debug", "${v.name} ${v.locale} q=${v.quality} lat=${v.latency} net=${v.isNetworkConnectionRequired} installed=$installed")
                }
                tts.shutdown()
                done.finish()
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
                } else if (name == "stt") {
                    recognizeSpoken(app, intent.getStringExtra("text").orEmpty(), intent.getIntExtra("tail", 4_000), intent.getIntExtra("noise", 0))
                } else if (name == "browse") {
                    probeBrowser(app, intent.getStringExtra("pkg") ?: "com.google.android.apps.youtube.music")
                } else {
                    // As a turn does: tools that read settings synchronously (Home Assistant) need them loaded.
                    app.settings.current()
                    val tool = app.tools.firstOrNull { it.spec.name == name }
                        ?: return@launch EventLog.log("debug", "No tool '$name'")
                    val parsed = Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}").jsonObject
                    val args = JsonObject(parsed - "confirmed")
                    var outcome = tool.invoke(args)
                    EventLog.log("debug", "$name($args) -> ${outcome.result}")
                    // "confirmed": true stands in for the user's yes to an action the tool held back.
                    val pending = outcome.pending
                    if (pending != null && parsed.boolean("confirmed") == true) {
                        outcome = pending.run()
                        EventLog.log("debug", "Confirmed ${pending.description} -> ${outcome.result}")
                    }
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

    /** Synthesizes [text] and runs it through [OnDeviceStt] at real-time pace, between stretches of silence. */
    private suspend fun recognizeSpoken(app: OrbitApp, text: String, tailMs: Int, noiseDbfs: Int) {
        val rate = com.hisbaan.orbit.audio.MicCapture.SAMPLE_RATE
        val speech = synthesize(app, text) ?: return EventLog.log("debug", "stt: synthesis failed")
        val chunk = rate / 50
        val audio = kotlinx.coroutines.channels.Channel<ShortArray>(capacity = 500)
        app.appScope.launch {
            val all = ShortArray(rate) + speech + ShortArray(rate * tailMs / 1000)
            if (noiseDbfs < 0) {
                val amp = 32767 * Math.pow(10.0, noiseDbfs / 20.0) * Math.sqrt(3.0)
                for (i in all.indices) all[i] = (all[i] + (Math.random() * 2 - 1) * amp).toInt().coerceIn(-32768, 32767).toShort()
            }
            for (i in all.indices step chunk) {
                if (audio.trySend(all.copyOfRange(i, minOf(i + chunk, all.size))).isClosed) break
                kotlinx.coroutines.delay(20)
            }
            audio.close()
        }
        EventLog.log("debug", "stt: feeding ${speech.size * 1000L / rate}ms of \"$text\", tail ${tailMs}ms, noise $noiseDbfs")
        val result = com.hisbaan.orbit.speech.OnDeviceStt(app).listen(audio, rate, language = null, onPartial = {
            EventLog.log("debug", "stt partial: \"$it\"")
        })
        audio.cancel()
        EventLog.log("debug", "stt result: $result")
    }

    /** [text] spoken by the phone's TTS, at the capture rate. */
    private suspend fun synthesize(app: OrbitApp, text: String): ShortArray? {
        val file = java.io.File(app.cacheDir, "stt-test.wav")
        val synthesized = CompletableDeferred<Boolean>()
        lateinit var tts: android.speech.tts.TextToSpeech
        withContext(Dispatchers.Main) {
            tts = android.speech.tts.TextToSpeech(app) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) return@TextToSpeech synthesized.complete(false).let { }
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { synthesized.complete(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { synthesized.complete(false) }
                })
                tts.synthesizeToFile(text, Bundle(), file, "stt-test")
            }
        }
        val ok = withTimeoutOrNull(15_000) { synthesized.await() } == true
        tts.shutdown()
        return if (ok) wavTo16k(file.readBytes(), com.hisbaan.orbit.audio.MicCapture.SAMPLE_RATE) else null
    }

    /** PCM16 WAV (any rate, first channel) resampled linearly to [rate]. */
    private fun wavTo16k(bytes: ByteArray, rate: Int): ShortArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var srcRate = rate
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") {
                channels = buf.getShort(pos + 10).toInt()
                srcRate = buf.getInt(pos + 12)
            } else if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2 / channels
                val src = ShortArray(n) { buf.getShort(pos + 8 + it * 2 * channels) }
                val out = (n.toLong() * rate / srcRate).toInt()
                return ShortArray(out) {
                    val x = it.toDouble() * srcRate / rate
                    val i = x.toInt().coerceAtMost(n - 2)
                    val f = x - i
                    (src[i] * (1 - f) + src[i + 1] * f).toInt().toShort()
                }
            }
            pos += 8 + size
        }
        return ShortArray(0)
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
