package com.hisbaan.orbit.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.hisbaan.orbit.homeassistant.HaCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    /** Reversible, so tests can read what was stored; [readable] false acts like another phone's Keystore. */
    private class FakeCipher(var readable: Boolean = true) : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (readable) encoded.removePrefix("enc:") else null
    }

    private val settingsStore = store("settings")
    private val secretsStore = store("secrets")
    private val cipher = FakeCipher()

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") }

    private fun repository() = SettingsRepository(settingsStore, secretsStore, scope, cipher)

    private val apiKey = stringPreferencesKey("api_key_encrypted")
    private val setSecrets = stringSetPreferencesKey("set_secrets")

    @Test
    fun `moves secrets out of the backed-up settings`() = runBlocking {
        // As earlier versions stored them: next to everything else.
        settingsStore.edit {
            it[stringPreferencesKey("model")] = "gpt-6-luna"
            it[apiKey] = "enc:sk-123"
        }
        val repo = repository()

        settingsStore.data.first { it[apiKey] == null }
        assertEquals("enc:sk-123", secretsStore.data.first()[apiKey])
        assertEquals(setOf("API_KEY"), settingsStore.data.first()[setSecrets])
        val settings = repo.current()
        assertEquals("sk-123", settings.apiKey)
        assertEquals("gpt-6-luna", settings.model)
        assertTrue(settings.missingSecrets.isEmpty())
    }

    @Test
    fun `secrets go to their own store, and the settings note which are set`() = runBlocking {
        val repo = repository()
        repo.update { it.copy(model = "m", apiKey = "sk-1", homeAssistantUrl = "http://ha.lan", homeAssistant = HaCredential.LongLived("t")) }

        assertEquals("enc:sk-1", secretsStore.data.first()[apiKey])
        assertNull(settingsStore.data.first()[apiKey])
        assertEquals(setOf("API_KEY", "HOME_ASSISTANT"), settingsStore.data.first()[setSecrets])
    }

    @Test
    fun `after a restore, says which secrets to enter again`() = runBlocking {
        // The settings came back from a backup; the secrets didn't.
        settingsStore.edit {
            it[stringPreferencesKey("model")] = "m"
            it[stringPreferencesKey("home_assistant_url")] = "http://ha.lan"
            it[stringPreferencesKey("weather_provider")] = "OPEN_METEO"
            it[setSecrets] = setOf("API_KEY", "HOME_ASSISTANT", "PIRATE_WEATHER_KEY")
        }
        val repo = repository()

        var settings = repo.current()
        assertEquals(setOf(Secret.API_KEY, Secret.HOME_ASSISTANT, Secret.PIRATE_WEATHER_KEY), settings.missingSecrets)
        // The weather key doesn't matter while another provider is chosen.
        assertEquals(listOf(Secret.API_KEY, Secret.HOME_ASSISTANT), settings.secretsToReenter)
        assertFalse(settings.isProviderConfigured)

        // Changing something else doesn't forget what's missing.
        repo.update { it.copy(musicPackage = "com.spotify.music") }
        assertEquals(3, repo.current().missingSecrets.size)

        // Entering the key, and disconnecting Home Assistant, clear theirs.
        repo.update { it.copy(apiKey = "sk-2") }
        repo.update { it.copy(missingSecrets = it.missingSecrets - Secret.HOME_ASSISTANT) }
        settings = repo.latest.first { it?.apiKey == "sk-2" && Secret.HOME_ASSISTANT !in it.missingSecrets }!!
        assertEquals(setOf(Secret.PIRATE_WEATHER_KEY), settings.missingSecrets)
        assertTrue(settings.isProviderConfigured)
    }

    @Test
    fun `secrets this phone can't decrypt count as missing`() = runBlocking {
        val repo = repository()
        repo.update { it.copy(model = "m", apiKey = "sk-1") }
        cipher.readable = false

        val settings = repository().current()
        assertEquals("", settings.apiKey)
        assertEquals(setOf(Secret.API_KEY), settings.missingSecrets)
    }
}
