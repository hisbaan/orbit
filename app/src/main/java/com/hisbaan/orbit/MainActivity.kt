package com.hisbaan.orbit

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hisbaan.orbit.assistant.AssistantActions
import com.hisbaan.orbit.assistant.AssistantScreen
import com.hisbaan.orbit.assistant.SetupStatus
import com.hisbaan.orbit.audio.label
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.miclab.MicLabActions
import com.hisbaan.orbit.miclab.MicLabScreen
import com.hisbaan.orbit.miclab.MicLabViewModel
import com.hisbaan.orbit.settings.SettingsScreen
import com.hisbaan.orbit.settings.SettingsViewModel
import com.hisbaan.orbit.ui.OrbitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private enum class Screen { ASSISTANT, SETTINGS, MIC_LAB }

    private val app get() = application as OrbitApp
    private val keyguardManager get() = getSystemService(KeyguardManager::class.java)
    private val micLab: MicLabViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    private var screen by mutableStateOf(Screen.ASSISTANT)
    private var missingPermissions by mutableStateOf(emptyList<String>())
    private var isDefaultAssistant by mutableStateOf(true)
    private var hasMediaAccess by mutableStateOf(true)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the screen the user was on after a rotation (onResume still leaves Settings if locked).
        savedInstanceState?.getString(STATE_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } }?.let { screen = it }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleTrigger(intent, "onCreate")

        setContent {
            OrbitTheme {
                BackHandler(enabled = screen != Screen.ASSISTANT) { screen = Screen.ASSISTANT }
                when (screen) {
                    Screen.ASSISTANT -> AssistantContent()
                    Screen.SETTINGS -> Scaffold { padding ->
                        val state by settingsVm.state.collectAsStateWithLifecycle()
                        SettingsScreen(state, settingsVm, onBack = { screen = Screen.ASSISTANT }, Modifier.padding(padding))
                    }
                    Screen.MIC_LAB -> {
                        val state by micLab.state.collectAsStateWithLifecycle()
                        val log by EventLog.entries.collectAsStateWithLifecycle()
                        MicLabScreen(
                            state = state,
                            log = log,
                            vm = micLab,
                            actions = MicLabActions(
                                back = { screen = Screen.ASSISTANT },
                                requestPermissions = ::requestPermissions,
                                openAssistantSettings = ::openAssistantSettings,
                                // The log holds transcripts and replies; the hail test shows Mic Lab while locked.
                                copyLog = { whenUnlocked(::copyLog) },
                                shareLog = { whenUnlocked(::shareLog) },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Built once, so the screen doesn't recompose its buttons on every state change. */
    private val assistantActions by lazy {
        AssistantActions(
            talk = { app.assistant.trigger("in-app button", null) },
            stop = app.assistant::cancel,
            grantPermissions = ::requestPermissions,
            openAssistantSettings = ::openAssistantSettings,
            openMediaAccessSettings = ::openMediaAccessSettings,
            openSettings = { whenUnlocked { screen = Screen.SETTINGS } },
            openMicLab = { whenUnlocked { screen = Screen.MIC_LAB } },
        )
    }

    @androidx.compose.runtime.Composable
    private fun AssistantContent() {
        val state by app.assistant.state.collectAsStateWithLifecycle()
        val settings by app.settings.latest.collectAsStateWithLifecycle()
        Scaffold { padding ->
            AssistantScreen(
                state = state,
                // Not loaded yet counts as configured, so the setup card doesn't flash on launch.
                setup = SetupStatus(missingPermissions, isDefaultAssistant, settings?.isProviderConfigured ?: true, hasMediaAccess),
                actions = assistantActions,
                modifier = Modifier.padding(padding),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, screen.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTrigger(intent, "onNewIntent")
    }

    override fun onResume() {
        super.onResume()
        // Belt and braces: Settings holds the API key and sends it to whatever endpoint is typed in.
        if (screen == Screen.SETTINGS && keyguardManager.isKeyguardLocked) screen = Screen.ASSISTANT
        refreshSetup()
    }

    override fun onStop() {
        super.onStop()
        // Shown over the lock screen for one forwarded trigger only: once Orbit leaves the
        // screen (it turns off, the user moves on), the lock screen covers the app again.
        setShowWhenLocked(false)
        setTurnScreenOn(false)
    }

    /** Runs [action] once the device is unlocked, asking for the PIN or fingerprint if it's locked. */
    private fun whenUnlocked(action: () -> Unit) {
        if (!keyguardManager.isKeyguardLocked) return action()
        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
            },
        )
    }

    private fun handleTrigger(intent: Intent, via: String) {
        val action = intent.action ?: return
        if (action !in TRIGGER_ACTIONS) return
        // Triggers forwarded here (hail test, or Orbit not the active assistant) can arrive with
        // the phone locked. Until onStop; the rest of the app stays behind the lock (whenUnlocked).
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val extras = intent.extras?.keySet()?.joinToString().orEmpty()
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        EventLog.log(
            "trigger",
            "Launched by $action via $via${if (extras.isNotEmpty()) " extras=[$extras]" else ""}" +
                (device?.let { " device=${it.label()}" } ?: ""),
        )
        val source = action.substringAfterLast('.')
        lifecycleScope.launch {
            if (app.settings.current().triggerRunsHailTest) {
                screen = Screen.MIC_LAB
                micLab.runHailTest(source, device)
            } else {
                screen = Screen.ASSISTANT
                app.assistant.trigger(source, device)
            }
        }
    }

    private fun refreshSetup() {
        missingPermissions = PERMISSIONS.filter { (permission, _) ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.map { it.second }.distinct() // read and write calendar share a label
        isDefaultAssistant = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        hasMediaAccess = app.mediaSessions.hasAccess
        // Only while it's open: the first touch builds Mic Lab, which starts listening to audio events.
        if (screen == Screen.MIC_LAB) micLab.refresh()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(PERMISSIONS.map { it.first }.toTypedArray())
    }

    private fun openAssistantSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: return
        startActivity(intent)
    }

    private fun openMediaAccessSettings() {
        val component = app.mediaSessions.listenerComponent.flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        startActivity(if (detail.resolveActivity(packageManager) != null) detail else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Orbit event log", EventLog.dump(micLab.logHeader())))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Orbit event log")
            .putExtra(Intent.EXTRA_TEXT, EventLog.dump(micLab.logHeader()))
        startActivity(Intent.createChooser(send, "Share log"))
    }

    companion object {
        private const val STATE_SCREEN = "screen"

        private val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_VOICE_COMMAND,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE,
            Intent.ACTION_ASSIST,
        )

        /** Runtime permissions and how they're described to the user. */
        private val PERMISSIONS = listOfNotNull(
            Manifest.permission.RECORD_AUDIO to "microphone",
            Manifest.permission.BLUETOOTH_CONNECT to "nearby devices",
            Manifest.permission.READ_CONTACTS to "contacts",
            Manifest.permission.CALL_PHONE to "phone calls",
            Manifest.permission.ACCESS_COARSE_LOCATION to "location (weather)",
            Manifest.permission.READ_CALENDAR to "calendar",
            Manifest.permission.WRITE_CALENDAR to "calendar",
            // Android 17 gates connections to LAN addresses (Home Assistant, local model servers).
            (Manifest.permission.ACCESS_LOCAL_NETWORK to "local network").takeIf { Build.VERSION.SDK_INT >= 37 },
        )
    }
}
