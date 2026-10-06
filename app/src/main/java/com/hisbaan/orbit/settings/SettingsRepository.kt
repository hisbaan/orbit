package com.hisbaan.orbit.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.homeassistant.HaCredential
import com.hisbaan.orbit.tools.SavedPlaylist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "",
    /** Sent as `reasoning_effort`; null leaves it to the provider. */
    val reasoningEffort: String? = null,
    /** Preferred music app for play_music; null lets the system choose. */
    val musicPackage: String? = null,
    /** The user's own YouTube Music playlists, by spoken name. */
    val savedPlaylists: List<SavedPlaylist> = emptyList(),
    /** Headset/assistant triggers run the Mic Lab hail test instead of the assistant. */
    val triggerRunsHailTest: Boolean = false,
    /** Home Assistant instance, e.g. `https://home.example.com` or `http://192.168.1.5:8123`. */
    val homeAssistantUrl: String = "",
    /** Null until the user signs in or adds a token. Stored encrypted. */
    val homeAssistant: HaCredential? = null,
    /** Listening sounds per headset, by Bluetooth address (e.g. alerting for a helmet). */
    val headsetSounds: Map<String, EarconStyle> = emptyMap(),
    /** Listening sounds on the phone and on headsets without their own choice. */
    val defaultSounds: EarconStyle = EarconStyle.GENTLE,
    /** TTS voice name (e.g. `en-us-x-tpd-local`); null uses the engine's default. */
    val ttsVoice: String? = null,
) {
    val isProviderConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high")
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val apiKeyEncrypted = stringPreferencesKey("api_key_encrypted")
        val model = stringPreferencesKey("model")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        val musicPackage = stringPreferencesKey("music_package")
        val triggerRunsHailTest = booleanPreferencesKey("trigger_runs_hail_test")
        val savedPlaylists = stringPreferencesKey("saved_playlists")
        val homeAssistantUrl = stringPreferencesKey("home_assistant_url")
        val homeAssistantEncrypted = stringPreferencesKey("home_assistant_credential_encrypted")
        /** "address=STYLE" entries. */
        val headsetSounds = stringSetPreferencesKey("headset_sounds")
        val defaultSounds = stringPreferencesKey("default_sounds")
        val ttsVoice = stringPreferencesKey("tts_voice")

        /** Before silent existed: the addresses that were alerting. Read once, then replaced by [headsetSounds]. */
        val legacyAlertingDevices = stringSetPreferencesKey("alerting_sound_devices")
    }

    val settings: Flow<AppSettings> = store.data.map(::fromPrefs)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val old = fromPrefs(prefs)
            val new = transform(old)
            prefs[Keys.baseUrl] = new.baseUrl.trim()
            prefs[Keys.model] = new.model.trim()
            prefs[Keys.triggerRunsHailTest] = new.triggerRunsHailTest
            prefs[Keys.savedPlaylists] = encodePlaylists(new.savedPlaylists)
            if (new.reasoningEffort != null) prefs[Keys.reasoningEffort] = new.reasoningEffort else prefs.remove(Keys.reasoningEffort)
            if (new.musicPackage != null) prefs[Keys.musicPackage] = new.musicPackage else prefs.remove(Keys.musicPackage)
            prefs[Keys.homeAssistantUrl] = new.homeAssistantUrl.trim()
            prefs[Keys.headsetSounds] = new.headsetSounds.map { (address, style) -> "$address=${style.name}" }.toSet()
            prefs[Keys.defaultSounds] = new.defaultSounds.name
            if (new.ttsVoice != null) prefs[Keys.ttsVoice] = new.ttsVoice else prefs.remove(Keys.ttsVoice)
            prefs.remove(Keys.legacyAlertingDevices)
            if (new.homeAssistant != old.homeAssistant) {
                val encoded = encodeCredential(new.homeAssistant)
                if (encoded == null) prefs.remove(Keys.homeAssistantEncrypted) else prefs[Keys.homeAssistantEncrypted] = SecretStore.encrypt(encoded)
            }
            if (new.apiKey != old.apiKey) {
                if (new.apiKey.isBlank()) prefs.remove(Keys.apiKeyEncrypted) else prefs[Keys.apiKeyEncrypted] = SecretStore.encrypt(new.apiKey.trim())
            }
        }
    }

    private fun fromPrefs(prefs: Preferences) = AppSettings(
        baseUrl = prefs[Keys.baseUrl] ?: AppSettings.DEFAULT_BASE_URL,
        apiKey = prefs[Keys.apiKeyEncrypted]?.let(SecretStore::decrypt).orEmpty(),
        model = prefs[Keys.model].orEmpty(),
        reasoningEffort = prefs[Keys.reasoningEffort],
        musicPackage = prefs[Keys.musicPackage],
        triggerRunsHailTest = prefs[Keys.triggerRunsHailTest] ?: false,
        savedPlaylists = prefs[Keys.savedPlaylists]?.let(::decodePlaylists).orEmpty(),
        homeAssistantUrl = prefs[Keys.homeAssistantUrl].orEmpty(),
        homeAssistant = prefs[Keys.homeAssistantEncrypted]?.let(SecretStore::decrypt)?.let(::decodeCredential),
        headsetSounds = prefs[Keys.headsetSounds]?.let(::decodeHeadsetSounds)
            ?: prefs[Keys.legacyAlertingDevices].orEmpty().associateWith { EarconStyle.ALERTING },
        defaultSounds = prefs[Keys.defaultSounds]?.let { name -> EarconStyle.entries.firstOrNull { it.name == name } } ?: EarconStyle.GENTLE,
        ttsVoice = prefs[Keys.ttsVoice],
    )

    private fun decodeHeadsetSounds(entries: Set<String>): Map<String, EarconStyle> = entries.mapNotNull { entry ->
        val (address, name) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        EarconStyle.entries.firstOrNull { it.name == name }?.let { address to it }
    }.toMap()

    private fun encodeCredential(credential: HaCredential?): String? = when (credential) {
        is HaCredential.OAuth -> "oauth\n${credential.refreshToken}"
        is HaCredential.LongLived -> "token\n${credential.token}"
        null -> null
    }

    private fun decodeCredential(encoded: String): HaCredential? {
        val (kind, secret) = encoded.split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "oauth" -> HaCredential.OAuth(secret)
            "token" -> HaCredential.LongLived(secret)
            else -> null
        }
    }

    private fun encodePlaylists(playlists: List<SavedPlaylist>): String = JSONArray(
        playlists.map { JSONObject().put("name", it.name).put("id", it.playlistId) },
    ).toString()

    private fun decodePlaylists(json: String): List<SavedPlaylist> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { i -> array.getJSONObject(i).let { SavedPlaylist(it.getString("name"), it.getString("id")) } }
    }.getOrDefault(emptyList())
}
