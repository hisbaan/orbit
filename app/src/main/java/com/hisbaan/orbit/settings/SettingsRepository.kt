package com.hisbaan.orbit.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.homeassistant.HaCredential
import com.hisbaan.orbit.tools.SavedPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    /** Calendar provider id new events go to; null picks a primary calendar. */
    val defaultCalendarId: Long? = null,
    val weatherProvider: WeatherSource = WeatherSource.OPEN_METEO,
    /** API keys for the keyed weather providers. Stored encrypted. */
    val pirateWeatherKey: String = "",
    val googleWeatherKey: String = "",
) {
    val isProviderConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high")
    }
}

/** Where forecasts come from. Open-Meteo needs no key; the others use [AppSettings]' keys. */
enum class WeatherSource(val label: String, val about: String) {
    OPEN_METEO("Open-Meteo", "Free, no key needed."),
    PIRATE_WEATHER("Pirate Weather", "The source behind Merry Sky: high-resolution national models. Free key from pirateweather.net."),
    GOOGLE("Google", "The data behind Google's weather app. Needs a Google Cloud API key with the Weather API enabled (billed beyond Google's free usage)."),
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * The app's settings. [latest] is the one copy everything reads: loaded once when the
 * repository is created (secrets decrypted then, not on every read) and kept current.
 */
class SettingsRepository(context: Context, scope: CoroutineScope) {
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
        val defaultCalendarId = longPreferencesKey("default_calendar_id")
        val weatherProvider = stringPreferencesKey("weather_provider")
        val pirateWeatherKey = stringPreferencesKey("pirate_weather_key_encrypted")
        val googleWeatherKey = stringPreferencesKey("google_weather_key_encrypted")

        /** Before silent existed: the addresses that were alerting. Read once, then replaced by [headsetSounds]. */
        val legacyAlertingDevices = stringSetPreferencesKey("alerting_sound_devices")
    }

    /** Null until the first load from disk, which starts as soon as the repository exists. */
    val latest: StateFlow<AppSettings?> = store.data.map(::fromPrefs).stateIn(scope, SharingStarted.Eagerly, null)

    /** The current settings, waiting for the first load if it hasn't finished. */
    suspend fun current(): AppSettings = latest.filterNotNull().first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val old = fromPrefs(prefs)
            val new = transform(old)
            prefs[Keys.baseUrl] = new.baseUrl.trim()
            prefs[Keys.model] = new.model.trim()
            prefs[Keys.triggerRunsHailTest] = new.triggerRunsHailTest
            prefs[Keys.savedPlaylists] = SavedPlaylists.encode(new.savedPlaylists)
            if (new.reasoningEffort != null) prefs[Keys.reasoningEffort] = new.reasoningEffort else prefs.remove(Keys.reasoningEffort)
            if (new.musicPackage != null) prefs[Keys.musicPackage] = new.musicPackage else prefs.remove(Keys.musicPackage)
            prefs[Keys.homeAssistantUrl] = new.homeAssistantUrl.trim()
            prefs[Keys.headsetSounds] = new.headsetSounds.map { (address, style) -> "$address=${style.name}" }.toSet()
            prefs[Keys.defaultSounds] = new.defaultSounds.name
            if (new.ttsVoice != null) prefs[Keys.ttsVoice] = new.ttsVoice else prefs.remove(Keys.ttsVoice)
            if (new.defaultCalendarId != null) prefs[Keys.defaultCalendarId] = new.defaultCalendarId else prefs.remove(Keys.defaultCalendarId)
            prefs[Keys.weatherProvider] = new.weatherProvider.name
            if (new.pirateWeatherKey != old.pirateWeatherKey) prefs.putSecret(Keys.pirateWeatherKey, new.pirateWeatherKey)
            if (new.googleWeatherKey != old.googleWeatherKey) prefs.putSecret(Keys.googleWeatherKey, new.googleWeatherKey)
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
        savedPlaylists = prefs[Keys.savedPlaylists]?.let(SavedPlaylists::decode).orEmpty(),
        homeAssistantUrl = prefs[Keys.homeAssistantUrl].orEmpty(),
        homeAssistant = prefs[Keys.homeAssistantEncrypted]?.let(SecretStore::decrypt)?.let(::decodeCredential),
        headsetSounds = prefs[Keys.headsetSounds]?.let(::decodeHeadsetSounds)
            ?: prefs[Keys.legacyAlertingDevices].orEmpty().associateWith { EarconStyle.ALERTING },
        defaultSounds = prefs[Keys.defaultSounds]?.let { name -> EarconStyle.entries.firstOrNull { it.name == name } } ?: EarconStyle.GENTLE,
        ttsVoice = prefs[Keys.ttsVoice],
        defaultCalendarId = prefs[Keys.defaultCalendarId],
        weatherProvider = prefs[Keys.weatherProvider]?.let { name -> WeatherSource.entries.firstOrNull { it.name == name } } ?: WeatherSource.OPEN_METEO,
        pirateWeatherKey = prefs[Keys.pirateWeatherKey]?.let(SecretStore::decrypt).orEmpty(),
        googleWeatherKey = prefs[Keys.googleWeatherKey]?.let(SecretStore::decrypt).orEmpty(),
    )

    private fun MutablePreferences.putSecret(key: Preferences.Key<String>, value: String) {
        if (value.isBlank()) remove(key) else this[key] = SecretStore.encrypt(value.trim())
    }

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
}

/** How saved playlists are stored: `[{"name": "Music", "id": "PL…"}]`. */
internal object SavedPlaylists {
    fun encode(playlists: List<SavedPlaylist>): String =
        JsonArray(playlists.map { buildJsonObject { put("name", it.name); put("id", it.playlistId) } }).toString()

    /** Entries that don't parse are dropped; a corrupt value reads as none. */
    fun decode(json: String): List<SavedPlaylist> {
        val array = try {
            Json.parseToJsonElement(json) as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        return array.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val id = (entry["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SavedPlaylist(name, id)
        }
    }
}
