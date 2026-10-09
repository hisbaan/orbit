package com.hisbaan.orbit.settings

import android.content.Context
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Secrets the user had set that aren't here: settings are backed up and restored, their
     * secrets aren't (they're encrypted with a key that never leaves the phone), so after a
     * restore these need entering again. Kept until re-entered or dismissed (removed here).
     */
    val missingSecrets: Set<Secret> = emptySet(),
) {
    val isProviderConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && Secret.API_KEY !in missingSecrets

    /** The missing secrets that matter with these settings (a weather key only for its provider). */
    val secretsToReenter: List<Secret>
        get() = missingSecrets.filter {
            when (it) {
                Secret.API_KEY -> true
                Secret.HOME_ASSISTANT -> homeAssistantUrl.isNotBlank()
                Secret.PIRATE_WEATHER_KEY -> weatherProvider == WeatherSource.PIRATE_WEATHER
                Secret.GOOGLE_WEATHER_KEY -> weatherProvider == WeatherSource.GOOGLE
            }
        }.sortedBy { it.ordinal }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high")
    }
}

/** The settings kept apart from the rest: encrypted, and not backed up (see [SettingsRepository]). */
enum class Secret(val label: String) {
    API_KEY("API key"),
    HOME_ASSISTANT("Home Assistant sign-in"),
    PIRATE_WEATHER_KEY("Pirate Weather key"),
    GOOGLE_WEATHER_KEY("Google Weather key"),
}

/** Where forecasts come from. Open-Meteo needs no key; the others use [AppSettings]' keys. */
enum class WeatherSource(val label: String, val about: String) {
    OPEN_METEO("Open-Meteo", "Free, no key needed."),
    PIRATE_WEATHER("Pirate Weather", "The source behind Merry Sky: high-resolution national models. Free key from pirateweather.net."),
    GOOGLE("Google", "The data behind Google's weather app. Needs a Google Cloud API key with the Weather API enabled (billed beyond Google's free usage)."),
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Separate from [settingsStore] so that file can be backed up without them (res/xml/data_extraction_rules.xml). */
private val Context.secretsStore by preferencesDataStore(name = "secrets")

/**
 * The app's settings. [latest] is the one copy everything reads: loaded once when the
 * repository is created (secrets decrypted then, not on every read) and kept current.
 *
 * Two stores: [settings] is backed up and copied to a new phone; [secrets] holds the
 * Keystore-encrypted API keys and sign-ins, which couldn't be decrypted anywhere else, so it
 * isn't. The settings remember which secrets were set, so a restore can say what to re-enter
 * ([AppSettings.missingSecrets]).
 */
class SettingsRepository(
    private val settings: DataStore<Preferences>,
    private val secrets: DataStore<Preferences>,
    scope: CoroutineScope,
    private val cipher: SecretCipher = SecretStore,
) {
    constructor(context: Context, scope: CoroutineScope) :
        this(context.applicationContext.settingsStore, context.applicationContext.secretsStore, scope)

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        val musicPackage = stringPreferencesKey("music_package")
        val triggerRunsHailTest = booleanPreferencesKey("trigger_runs_hail_test")
        val savedPlaylists = stringPreferencesKey("saved_playlists")
        val homeAssistantUrl = stringPreferencesKey("home_assistant_url")
        /** "address=STYLE" entries. */
        val headsetSounds = stringSetPreferencesKey("headset_sounds")
        val defaultSounds = stringPreferencesKey("default_sounds")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val defaultCalendarId = longPreferencesKey("default_calendar_id")
        val weatherProvider = stringPreferencesKey("weather_provider")

        /** [Secret] names the user has set, so a restore without them can tell. */
        val setSecrets = stringSetPreferencesKey("set_secrets")

        /** Before silent existed: the addresses that were alerting. Read once, then replaced by [headsetSounds]. */
        val legacyAlertingDevices = stringSetPreferencesKey("alerting_sound_devices")
    }

    /** Where each secret's ciphertext is kept: in [secrets], and in [settings] before they were split. */
    private val secretKeys = mapOf(
        Secret.API_KEY to stringPreferencesKey("api_key_encrypted"),
        Secret.HOME_ASSISTANT to stringPreferencesKey("home_assistant_credential_encrypted"),
        Secret.PIRATE_WEATHER_KEY to stringPreferencesKey("pirate_weather_key_encrypted"),
        Secret.GOOGLE_WEATHER_KEY to stringPreferencesKey("google_weather_key_encrypted"),
    )

    /** One update at a time: an update reads both stores and writes both. */
    private val updates = Mutex()

    /** Null until the first load from disk, which starts as soon as the repository exists. */
    val latest: StateFlow<AppSettings?> = combine(settings.data, secrets.data, ::fromPrefs).stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch { moveSecretsOutOfSettings() }
    }

    /** The current settings, waiting for the first load if it hasn't finished. */
    suspend fun current(): AppSettings = latest.filterNotNull().first()

    suspend fun update(transform: (AppSettings) -> AppSettings) = updates.withLock {
        val old = fromPrefs(settings.data.first(), secrets.data.first())
        val new = transform(old)
        val oldSecrets = secretValues(old)
        val newSecrets = secretValues(new)
        val changed = newSecrets.filter { (secret, value) -> value != oldSecrets[secret] }
        if (changed.isNotEmpty()) {
            secrets.edit { prefs ->
                for ((secret, value) in changed) {
                    val key = secretKeys.getValue(secret)
                    if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = cipher.encrypt(value.trim())
                }
            }
        }
        settings.edit { prefs ->
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
            prefs.remove(Keys.legacyAlertingDevices)
            // Set now, or set before and still to be re-entered.
            val set = newSecrets.filterValues { !it.isNullOrBlank() }.keys + new.missingSecrets
            prefs[Keys.setSecrets] = set.mapTo(mutableSetOf()) { it.name }
        }
    }

    /**
     * Secrets used to be kept with the rest of the settings. Copies them into [secrets], then
     * removes them from [settings], in that order so nothing is lost if this is cut short;
     * [fromPrefs] reads the old place meanwhile.
     */
    private suspend fun moveSecretsOutOfSettings() = updates.withLock {
        val plain = settings.data.first()
        val found = secretKeys.filterValues { plain[it] != null }
        if (found.isEmpty()) return@withLock
        secrets.edit { prefs -> found.values.forEach { key -> if (prefs[key] == null) prefs[key] = plain[key]!! } }
        settings.edit { prefs ->
            found.values.forEach { prefs.remove(it) }
            prefs[Keys.setSecrets] = prefs[Keys.setSecrets].orEmpty() + found.keys.map { it.name }
        }
    }

    private fun fromPrefs(plain: Preferences, secretPrefs: Preferences): AppSettings {
        fun secret(secret: Secret): String? {
            val key = secretKeys.getValue(secret)
            return (secretPrefs[key] ?: plain[key])?.let(cipher::decrypt)?.takeIf { it.isNotBlank() }
        }
        val values = Secret.entries.associateWith(::secret)
        val homeAssistant = values[Secret.HOME_ASSISTANT]?.let(::decodeCredential)
        val set = plain[Keys.setSecrets].orEmpty().mapNotNull { name -> Secret.entries.firstOrNull { it.name == name } }
        return AppSettings(
            baseUrl = plain[Keys.baseUrl] ?: AppSettings.DEFAULT_BASE_URL,
            apiKey = values[Secret.API_KEY].orEmpty(),
            model = plain[Keys.model].orEmpty(),
            reasoningEffort = plain[Keys.reasoningEffort],
            musicPackage = plain[Keys.musicPackage],
            triggerRunsHailTest = plain[Keys.triggerRunsHailTest] ?: false,
            savedPlaylists = plain[Keys.savedPlaylists]?.let(SavedPlaylists::decode).orEmpty(),
            homeAssistantUrl = plain[Keys.homeAssistantUrl].orEmpty(),
            homeAssistant = homeAssistant,
            headsetSounds = plain[Keys.headsetSounds]?.let(::decodeHeadsetSounds)
                ?: plain[Keys.legacyAlertingDevices].orEmpty().associateWith { EarconStyle.ALERTING },
            defaultSounds = plain[Keys.defaultSounds]?.let { name -> EarconStyle.entries.firstOrNull { it.name == name } } ?: EarconStyle.GENTLE,
            ttsVoice = plain[Keys.ttsVoice],
            defaultCalendarId = plain[Keys.defaultCalendarId],
            weatherProvider = plain[Keys.weatherProvider]?.let { name -> WeatherSource.entries.firstOrNull { it.name == name } } ?: WeatherSource.OPEN_METEO,
            pirateWeatherKey = values[Secret.PIRATE_WEATHER_KEY].orEmpty(),
            googleWeatherKey = values[Secret.GOOGLE_WEATHER_KEY].orEmpty(),
            missingSecrets = set.filterTo(mutableSetOf()) { values[it] == null || (it == Secret.HOME_ASSISTANT && homeAssistant == null) },
        )
    }

    /** Each secret's plain value in [s], as stored. */
    private fun secretValues(s: AppSettings): Map<Secret, String?> = mapOf(
        Secret.API_KEY to s.apiKey,
        Secret.HOME_ASSISTANT to encodeCredential(s.homeAssistant),
        Secret.PIRATE_WEATHER_KEY to s.pirateWeatherKey,
        Secret.GOOGLE_WEATHER_KEY to s.googleWeatherKey,
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
