package com.hisbaan.orbit

import android.app.Application
import android.content.pm.ApplicationInfo
import com.hisbaan.orbit.agent.ShowInfoCardTool
import com.hisbaan.orbit.assist.OrbitSession
import com.hisbaan.orbit.assist.OrbitVoiceInteractionService
import com.hisbaan.orbit.assist.UnlockActivity
import com.hisbaan.orbit.assistant.Assistant
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.homeassistant.HaCredential
import com.hisbaan.orbit.homeassistant.HomeAssistant
import com.hisbaan.orbit.homeassistant.HomeTools
import com.hisbaan.orbit.settings.AppSettings
import com.hisbaan.orbit.settings.SettingsRepository
import com.hisbaan.orbit.settings.WeatherSource
import com.hisbaan.orbit.speech.TtsSpeaker
import com.hisbaan.orbit.tools.CalendarAccess
import com.hisbaan.orbit.tools.CalendarEventsTool
import com.hisbaan.orbit.tools.CallContactTool
import com.hisbaan.orbit.tools.CreateCalendarEventTool
import com.hisbaan.orbit.tools.CurrentTimeTool
import com.hisbaan.orbit.tools.DeleteCalendarEventTool
import com.hisbaan.orbit.tools.LatestForecast
import com.hisbaan.orbit.tools.MediaControlTool
import com.hisbaan.orbit.tools.MediaInfoTool
import com.hisbaan.orbit.tools.MediaSessions
import com.hisbaan.orbit.tools.NavigationTool
import com.hisbaan.orbit.tools.NotificationsTool
import com.hisbaan.orbit.tools.OpenAppTool
import com.hisbaan.orbit.tools.PlayMusicTool
import com.hisbaan.orbit.tools.PlaySavedPlaylistTool
import com.hisbaan.orbit.tools.ReadScreenTool
import com.hisbaan.orbit.tools.SetAlarmTool
import com.hisbaan.orbit.tools.SetTimerTool
import com.hisbaan.orbit.tools.ShowWeatherCardTool
import com.hisbaan.orbit.tools.WeatherTool
import com.hisbaan.orbit.weather.GoogleWeather
import com.hisbaan.orbit.weather.OpenMeteo
import com.hisbaan.orbit.weather.PirateWeather
import com.hisbaan.orbit.weather.WeatherProvider
import com.hisbaan.orbit.ytmusic.YouTubeMusicSearch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

class OrbitApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(this, appScope) }

    val httpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 90_000
            }
        }
    }

    val mediaSessions by lazy { MediaSessions(this) }

    private var homeAssistantCache: Triple<String, HaCredential?, HomeAssistant?>? = null

    /**
     * The connected Home Assistant, rebuilt only when its settings change (it caches access tokens).
     * Read synchronously (tool lists are built before each model call); every turn starts by
     * loading settings, so they're in by then.
     */
    @Synchronized
    private fun homeAssistant(): HomeAssistant? {
        val s = settings.latest.value ?: return null
        homeAssistantCache?.let { (url, credential, ha) -> if (url == s.homeAssistantUrl && credential == s.homeAssistant) return ha }
        val credential = s.homeAssistant
        val ha = if (s.homeAssistantUrl.isBlank() || credential == null) null else HomeAssistant(httpClient, s.homeAssistantUrl, credential)
        homeAssistantCache = Triple(s.homeAssistantUrl, credential, ha)
        return ha
    }

    private val openMeteo by lazy { OpenMeteo(httpClient) }
    private val latestForecast = LatestForecast()

    /** The forecast source [settings] choose; Open-Meteo when it needs a key that isn't set. */
    fun weatherProvider(settings: AppSettings): WeatherProvider = when (settings.weatherProvider) {
        WeatherSource.OPEN_METEO -> null
        WeatherSource.PIRATE_WEATHER -> settings.pirateWeatherKey.takeIf { it.isNotBlank() }?.let { PirateWeather(httpClient, it) }
        WeatherSource.GOOGLE -> settings.googleWeatherKey.takeIf { it.isNotBlank() }?.let { GoogleWeather(httpClient, it) }
    } ?: openMeteo

    val tools by lazy {
        listOf(
            MediaControlTool(this, mediaSessions),
            MediaInfoTool(this, mediaSessions),
            PlayMusicTool(this, mediaSessions, YouTubeMusicSearch(httpClient)) { settings.current().musicPackage },
            PlaySavedPlaylistTool(this, mediaSessions) { settings.latest.value?.savedPlaylists.orEmpty() },
            NavigationTool(this),
            SetTimerTool(this),
            SetAlarmTool(this),
            CurrentTimeTool(),
            WeatherTool(this, openMeteo, provider = { weatherProvider(settings.current()) }, latest = latestForecast),
            ShowWeatherCardTool(this, latestForecast),
            ShowInfoCardTool(),
            NotificationsTool(this),
            ReadScreenTool(this),
            CallContactTool(this),
            OpenAppTool(this),
        ) + CalendarAccess(this).let { calendar ->
            listOf(
                CalendarEventsTool(calendar),
                CreateCalendarEventTool(calendar, defaultCalendarId = { settings.current().defaultCalendarId }),
                DeleteCalendarEventTool(calendar),
            )
        } + HomeTools(::homeAssistant, { Locale.getDefault().language }).all
    }

    /** Shared so Settings can list and preview the voices the assistant speaks with. */
    val tts by lazy { TtsSpeaker(this) }

    val assistant by lazy {
        Assistant(context = this, settings = settings, httpClient = httpClient, tools = tools, tts = tts).apply {
            keyguardDismisser = { UnlockActivity.request(this@OrbitApp) }
            overlay = object : Assistant.Overlay {
                override val isShown get() = OrbitSession.isShown
                override fun resume() = OrbitVoiceInteractionService.resumeOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.recordContent = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        EventLog.addSink(LogcatSink)
        EventLog.addSink(FileSink(filesDir))
        settings // start loading settings now, not at the first turn
    }
}
