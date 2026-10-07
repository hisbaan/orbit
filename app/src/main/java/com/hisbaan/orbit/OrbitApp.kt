package com.hisbaan.orbit

import android.app.Application
import com.hisbaan.orbit.assist.UnlockActivity
import com.hisbaan.orbit.assistant.Assistant
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.homeassistant.HomeAssistant
import com.hisbaan.orbit.homeassistant.HomeTools
import com.hisbaan.orbit.settings.SettingsRepository
import com.hisbaan.orbit.speech.TtsSpeaker
import com.hisbaan.orbit.tools.CalendarAccess
import com.hisbaan.orbit.tools.CalendarEventsTool
import com.hisbaan.orbit.tools.CallContactTool
import com.hisbaan.orbit.tools.CreateCalendarEventTool
import com.hisbaan.orbit.tools.CurrentTimeTool
import com.hisbaan.orbit.tools.DeleteCalendarEventTool
import com.hisbaan.orbit.tools.MediaControlTool
import com.hisbaan.orbit.tools.MediaInfoTool
import com.hisbaan.orbit.tools.MediaSessions
import com.hisbaan.orbit.tools.NavigationTool
import com.hisbaan.orbit.tools.NotificationsTool
import com.hisbaan.orbit.tools.OpenAppTool
import com.hisbaan.orbit.tools.PlayMusicTool
import com.hisbaan.orbit.tools.PlaySavedPlaylistTool
import com.hisbaan.orbit.tools.SetAlarmTool
import com.hisbaan.orbit.tools.SetTimerTool
import com.hisbaan.orbit.tools.WeatherTool
import com.hisbaan.orbit.weather.OpenMeteo
import com.hisbaan.orbit.ytmusic.YouTubeMusicSearch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class OrbitApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(this) }

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

    /** Settings that tools read synchronously, kept hot. */
    private val musicPackage by lazy {
        settings.settings.map { it.musicPackage }.stateIn(appScope, SharingStarted.Eagerly, null)
    }
    private val savedPlaylists by lazy {
        settings.settings.map { it.savedPlaylists }.stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    /** The connected Home Assistant, rebuilt when its settings change (it caches access tokens). */
    private val homeAssistant by lazy {
        settings.settings
            .map { it.homeAssistantUrl to it.homeAssistant }
            .distinctUntilChanged()
            .map { (url, credential) -> if (url.isBlank() || credential == null) null else HomeAssistant(httpClient, url, credential) }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    val tools by lazy {
        listOf(
            MediaControlTool(this, mediaSessions) { musicPackage.value },
            MediaInfoTool(this, mediaSessions) { musicPackage.value },
            PlayMusicTool(this, mediaSessions, YouTubeMusicSearch(httpClient)) { musicPackage.value },
            PlaySavedPlaylistTool(this, mediaSessions) { savedPlaylists.value },
            NavigationTool(this),
            SetTimerTool(this),
            SetAlarmTool(this),
            CurrentTimeTool(),
            WeatherTool(this, OpenMeteo(httpClient)),
            NotificationsTool(this),
            CallContactTool(this),
            OpenAppTool(this),
        ) + CalendarAccess(this).let { calendar ->
            listOf(
                CalendarEventsTool(calendar),
                CreateCalendarEventTool(calendar, defaultCalendarId = { settings.current().defaultCalendarId }),
                DeleteCalendarEventTool(calendar),
            )
        } + HomeTools({ homeAssistant.value }, { Locale.getDefault().language }).all
    }

    /** Shared so Settings can list and preview the voices the assistant speaks with. */
    val tts by lazy { TtsSpeaker(this) }

    val assistant by lazy {
        Assistant(context = this, settings = settings, httpClient = httpClient, tools = tools, tts = tts).apply {
            keyguardDismisser = { UnlockActivity.request(this@OrbitApp) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.addSink(LogcatSink)
        EventLog.addSink(FileSink(filesDir))
    }
}
