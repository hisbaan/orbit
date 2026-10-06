package com.hisbaan.orbit.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisbaan.orbit.OrbitApp
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.audio.Earcons
import com.hisbaan.orbit.audio.MicCapture
import com.hisbaan.orbit.audio.PcmPlayer
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.homeassistant.HaAuth
import com.hisbaan.orbit.homeassistant.HaCredential
import com.hisbaan.orbit.homeassistant.HaException
import com.hisbaan.orbit.homeassistant.HomeAssistant
import com.hisbaan.orbit.providers.ApiKeyCredential
import com.hisbaan.orbit.providers.OpenAiChatCompletions
import com.hisbaan.orbit.speech.isInstalled
import com.hisbaan.orbit.tools.SavedPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

data class MusicApp(val label: String, val packageName: String)

/** An installed TTS voice in the user's language. */
data class VoiceOption(val name: String, val label: String)

/** A paired Bluetooth headset (anything in the audio device class). */
data class Headset(val name: String, val address: String)

data class SettingsUiState(
    /** Null until loaded from disk. */
    val draft: AppSettings? = null,
    val dirty: Boolean = false,
    val models: List<String> = emptyList(),
    val status: String? = null,
    val musicApps: List<MusicApp> = emptyList(),
    /** Set while the Home Assistant sign-in page is open. */
    val haSignIn: HaSignIn? = null,
    val haStatus: String? = null,
    /** Paired headsets; null without the nearby devices permission. */
    val headsets: List<Headset>? = null,
    val voices: List<VoiceOption> = emptyList(),
)

data class HaSignIn(val baseUrl: String, val authorizeUrl: String, val state: String)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as OrbitApp).settings
    private val httpClient = (app as OrbitApp).httpClient
    private val tts = (app as OrbitApp).tts
    private var preview: Job? = null

    private val _state = MutableStateFlow(SettingsUiState(musicApps = findMusicApps()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(draft = repo.current()) } }
        loadHeadsets()
        loadVoices()
    }

    /** Edits that wait for an explicit save (text fields). */
    fun edit(transform: (AppSettings) -> AppSettings) =
        _state.update { s -> s.copy(draft = s.draft?.let(transform), dirty = true, status = null) }

    /** Edits that apply immediately (switches, pickers). */
    fun editAndSave(transform: (AppSettings) -> AppSettings) {
        edit(transform)
        save(quiet = true)
    }

    fun save(quiet: Boolean = false) {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            repo.update { draft }
            _state.update { it.copy(dirty = false, status = if (quiet) it.status else "Saved") }
        }
    }

    /** Returns an error message, or null when the playlist was added. */
    fun addPlaylist(name: String, link: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Give the playlist a name you'll say, e.g. \"Music\"."
        val id = SavedPlaylist.parsePlaylistId(link) ?: return "That doesn't look like a YouTube Music playlist link."
        editAndSave { s -> s.copy(savedPlaylists = s.savedPlaylists.filterNot { it.name.equals(trimmed, true) } + SavedPlaylist(trimmed, id)) }
        return null
    }

    fun removePlaylist(playlist: SavedPlaylist) = editAndSave { s -> s.copy(savedPlaylists = s.savedPlaylists - playlist) }

    fun loadModels() {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(status = "Loading models…") }
        viewModelScope.launch {
            try {
                val models = OpenAiChatCompletions(draft.baseUrl, ApiKeyCredential(draft.apiKey), httpClient).listModels()
                _state.update { it.copy(models = models, status = "${models.size} models") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "Couldn't load models: ${e.message}") }
            }
        }
    }

    // region Home Assistant

    /** Opens HA's own sign-in page (in a web view), for [url] as typed. */
    fun startHaSignIn(url: String) {
        val baseUrl = normalizeHaUrl(url) ?: return _state.update { it.copy(haStatus = "Enter the full address, e.g. https://home.example.com or http://192.168.1.5:8123") }
        val state = UUID.randomUUID().toString()
        _state.update { it.copy(haSignIn = HaSignIn(baseUrl, HaAuth.authorizeUrl(baseUrl, state), state), haStatus = null) }
    }

    fun cancelHaSignIn() = _state.update { it.copy(haSignIn = null) }

    /** Called for each page the sign-in web view is about to load; true when it was the redirect back to Orbit. */
    fun onHaSignInNavigation(url: String): Boolean {
        val signIn = _state.value.haSignIn ?: return false
        val code = HaAuth.codeFromRedirect(signIn.baseUrl, url, signIn.state) ?: return false
        _state.update { it.copy(haSignIn = null, haStatus = "Signing in…") }
        viewModelScope.launch {
            connectHa(signIn.baseUrl) {
                val tokens = HaAuth.exchangeCode(httpClient, signIn.baseUrl, code.getOrThrow())
                HaCredential.OAuth(tokens.refreshToken ?: throw HaException("Home Assistant returned no refresh token"))
            }
        }
        return true
    }

    fun useHaToken(url: String, token: String) {
        val baseUrl = normalizeHaUrl(url) ?: return _state.update { it.copy(haStatus = "Enter the full address, e.g. https://home.example.com") }
        if (token.isBlank()) return _state.update { it.copy(haStatus = "Paste a long-lived access token first.") }
        _state.update { it.copy(haStatus = "Checking…") }
        viewModelScope.launch { connectHa(baseUrl) { HaCredential.LongLived(token.trim()) } }
    }

    fun testHa() {
        val draft = _state.value.draft ?: return
        val credential = draft.homeAssistant ?: return
        _state.update { it.copy(haStatus = "Checking…") }
        viewModelScope.launch {
            val status = try {
                "Connected to ${HomeAssistant(httpClient, draft.homeAssistantUrl, credential).locationName()}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't reach Home Assistant: ${e.message}" + localNetworkHint()
            }
            _state.update { it.copy(haStatus = status) }
        }
    }

    fun disconnectHa() {
        val draft = _state.value.draft ?: return
        val credential = draft.homeAssistant
        viewModelScope.launch {
            // Best effort: drop the sign-in on the server too.
            if (credential is HaCredential.OAuth) runCatching { HaAuth.revoke(httpClient, draft.homeAssistantUrl, credential.refreshToken) }
            repo.update { it.copy(homeAssistant = null) }
            _state.update { s -> s.copy(draft = s.draft?.copy(homeAssistant = null), haStatus = "Disconnected.") }
        }
    }

    private suspend fun connectHa(baseUrl: String, credential: suspend () -> HaCredential) {
        val status = try {
            val cred = credential()
            val name = HomeAssistant(httpClient, baseUrl, cred).locationName()
            repo.update { it.copy(homeAssistantUrl = baseUrl, homeAssistant = cred) }
            _state.update { s -> s.copy(draft = s.draft?.copy(homeAssistantUrl = baseUrl, homeAssistant = cred)) }
            "Connected to $name."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't connect: ${e.message}" + localNetworkHint()
        }
        _state.update { it.copy(haStatus = status) }
    }

    /** Android 17 blocks LAN addresses (including a public name that resolves to one) without this permission. */
    private fun localNetworkHint(): String {
        if (Build.VERSION.SDK_INT < 37) return ""
        val granted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        return if (granted) "" else "\nIf Home Assistant is on your local network, grant Orbit the local network permission (setup card on the main screen)."
    }

    /** A usable base URL (scheme and host), or null. Users often paste a dashboard URL; only the origin matters. */
    private fun normalizeHaUrl(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return runCatching { HaAuth.origin(trimmed) }.getOrNull()?.takeIf { it.substringAfter("://").isNotBlank() }
    }

    // endregion

    // region Voice

    fun loadVoices() {
        viewModelScope.launch {
            val language = Locale.getDefault().language
            val voices = tts.voices()
                .filter { it.locale.language == language && it.isInstalled }
                .map { VoiceOption(it.name, voiceLabel(it)) }
                .sortedBy { it.label }
            _state.update { it.copy(voices = voices) }
        }
    }

    /** Picks [name] (null: the engine's default) and plays a sample of it. */
    fun selectVoice(name: String?) {
        editAndSave { it.copy(ttsVoice = name) }
        previewVoice(name)
    }

    fun previewVoice(name: String?) {
        preview?.cancel()
        preview = viewModelScope.launch {
            tts.voiceName = name
            tts.speak(VOICE_SAMPLE, PlaybackUsage.ASSISTANT)
        }
    }

    /** Android's text-to-speech settings, where more voices can be downloaded. */
    fun openTtsSettings() {
        getApplication<Application>().startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "United States · IOB", "(online)" for network voices; names look like `en-us-x-iob-network`. */
    private fun voiceLabel(voice: Voice): String {
        val region = voice.locale.displayCountry.ifBlank { voice.locale.displayLanguage }
        val code = voice.name.substringAfter("-x-", "").substringBeforeLast('-').uppercase().ifEmpty { "Standard" }
        return "$region · $code" + if (voice.isNetworkConnectionRequired) " (online)" else ""
    }

    // endregion

    // region Listening sounds

    fun loadHeadsets() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return _state.update { it.copy(headsets = null) }
        }
        val bonded = app.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        val headsets = bonded
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
            .map { Headset(it.alias ?: it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
        _state.update { it.copy(headsets = headsets) }
    }

    fun setHeadsetSounds(headset: Headset, style: EarconStyle) = editAndSave { s ->
        s.copy(headsetSounds = s.headsetSounds + (headset.address to style))
    }

    fun setDefaultSounds(style: EarconStyle) = editAndSave { it.copy(defaultSounds = style) }

    /** Plays the start and end sounds of [style] on the current media output. */
    fun previewSounds(style: EarconStyle) {
        viewModelScope.launch {
            PcmPlayer.play(Earcons.listening(style, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, PlaybackUsage.ASSISTANT)
            delay(600)
            PcmPlayer.play(Earcons.done(style, MicCapture.SAMPLE_RATE), MicCapture.SAMPLE_RATE, PlaybackUsage.ASSISTANT)
        }
    }

    // endregion

    private companion object {
        const val VOICE_SAMPLE = "Hi, I'm Orbit. The living room lights are off, and it's 14 degrees and partly cloudy."
    }

    private fun findMusicApps(): List<MusicApp> {
        val pm = getApplication<Application>().packageManager
        return pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .map { MusicApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
