package com.hisbaan.orbit.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hisbaan.orbit.audio.EarconStyle
import com.hisbaan.orbit.homeassistant.HaCredential
import com.hisbaan.orbit.tools.CalendarAccess

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft ?: return
    state.haSignIn?.let { signIn ->
        return HaSignInScreen(signIn, vm::onHaSignInNavigation, vm::cancelHaSignIn, modifier)
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }

        Section("Provider") {
            Text(
                "Any OpenAI-compatible Chat Completions endpoint with tool calling.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { v -> vm.edit { it.copy(baseUrl = v) } },
                label = { Text("Base URL") },
                singleLine = true,
                isError = sendsKeyInTheClear(draft.baseUrl),
                supportingText = if (sendsKeyInTheClear(draft.baseUrl)) {
                    { Text("Not encrypted: the API key would cross the internet in the clear. Use https unless this server is on your own network.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = { v -> vm.edit { it.copy(apiKey = v) } },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            var modelsOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { v -> vm.edit { it.copy(model = v) } },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = modelsOpen && state.models.isNotEmpty(), onDismissRequest = { modelsOpen = false }) {
                    state.models.forEach { model ->
                        DropdownMenuItem(text = { Text(model) }, onClick = {
                            modelsOpen = false
                            vm.edit { it.copy(model = model) }
                        })
                    }
                }
            }
            var effortOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reasoning effort")
                    Text(
                        "\"none\" is fastest. Some OpenAI models require it for tool use on this endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    TextButton(onClick = { effortOpen = true }) { Text(draft.reasoningEffort ?: "Provider default") }
                    DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                        (listOf<String?>(null) + AppSettings.REASONING_EFFORTS).forEach { effort ->
                            DropdownMenuItem(text = { Text(effort ?: "Provider default") }, onClick = {
                                effortOpen = false
                                vm.edit { it.copy(reasoningEffort = effort) }
                            })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.loadModels()
                    modelsOpen = true
                }) { Text("Pick model") }
                Button(onClick = { vm.save() }, enabled = state.dirty) { Text("Save") }
            }
            state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section("Music") {
            var open by remember { mutableStateOf(false) }
            val current = state.musicApps.firstOrNull { it.packageName == draft.musicPackage }?.label
                ?: draft.musicPackage ?: "System default"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Play music with", modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { open = true }) { Text(current) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("System default") }, onClick = {
                            open = false
                            vm.editAndSave { it.copy(musicPackage = null) }
                        })
                        state.musicApps.forEach { app ->
                            DropdownMenuItem(text = { Text(app.label) }, onClick = {
                                open = false
                                vm.editAndSave { it.copy(musicPackage = app.packageName) }
                            })
                        }
                    }
                }
            }
        }

        Section("Saved playlists") {
            Text(
                "Your own YouTube Music playlists, by the name you'll say (\"play my Music playlist\"). " +
                    "In YouTube Music: playlist → Share → Copy link.",
                style = MaterialTheme.typography.bodySmall,
            )
            draft.savedPlaylists.forEach { playlist ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(playlist.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.removePlaylist(playlist) }) { Text("Remove") }
                }
            }
            var name by remember { mutableStateOf("") }
            var link by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Playlist link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                error = vm.addPlaylist(name, link)
                if (error == null) {
                    name = ""
                    link = ""
                }
            }) { Text("Add playlist") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        Section("Weather") {
            WeatherSettings(draft, state.dirty, state.weatherStatus, vm)
        }

        Section("Calendar") {
            CalendarSettings(draft, state.calendars, vm)
        }

        Section("Voice") {
            VoiceSettings(draft, state.voices, vm)
        }

        Section("Listening sounds") {
            ListeningSounds(draft, state.headsets, vm)
        }

        Section("Home Assistant") {
            HomeAssistantSettings(draft, state.haStatus, vm)
        }

        Section("Debug") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Triggers run the Mic Lab hail test instead of the assistant", modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.triggerRunsHailTest,
                    onCheckedChange = { checked -> vm.editAndSave { it.copy(triggerRunsHailTest = checked) } },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun WeatherSettings(draft: AppSettings, dirty: Boolean, status: String?, vm: SettingsViewModel) {
    var open by remember { mutableStateOf(false) }
    val source = draft.weatherProvider
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Forecasts from", modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(source.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                WeatherSource.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        open = false
                        vm.editAndSave { it.copy(weatherProvider = option) }
                    })
                }
            }
        }
    }
    Text(source.about, style = MaterialTheme.typography.bodySmall)
    val key = when (source) {
        WeatherSource.OPEN_METEO -> null
        WeatherSource.PIRATE_WEATHER -> draft.pirateWeatherKey
        WeatherSource.GOOGLE -> draft.googleWeatherKey
    }
    if (key != null) {
        OutlinedTextField(
            value = key,
            onValueChange = { v ->
                vm.edit { if (source == WeatherSource.GOOGLE) it.copy(googleWeatherKey = v) else it.copy(pirateWeatherKey = v) }
            },
            label = { Text("${source.label} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (key.isBlank()) Text("Without a key, Orbit uses Open-Meteo.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::testWeather) { Text("Test") }
        if (key != null) Button(onClick = { vm.save() }, enabled = dirty) { Text("Save") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun CalendarSettings(draft: AppSettings, calendars: List<CalendarAccess.Calendar>?, vm: SettingsViewModel) {
    if (calendars == null) {
        Text("Grant the calendar permission to let Orbit read and add events.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var open by remember { mutableStateOf(false) }
    val chosen = calendars.firstOrNull { it.id == draft.defaultCalendarId }
    val fallback = CalendarAccess.pick(calendars, null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("New events go to", modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = {
                vm.loadCalendars()
                open = true
            }) { Text(chosen?.name ?: fallback?.let { "${it.name} (automatic)" } ?: "No writable calendar") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Automatic (a primary calendar)") }, onClick = {
                    open = false
                    vm.setDefaultCalendar(null)
                })
                calendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = { Text(if (calendar.account != calendar.name) "${calendar.name} (${calendar.account})" else calendar.name) },
                        onClick = {
                            open = false
                            vm.setDefaultCalendar(calendar.id)
                        },
                    )
                }
            }
        }
    }
    Text("Asking for another calendar by name (\"add it to my Work calendar\") still works.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VoiceSettings(draft: AppSettings, voices: List<VoiceOption>, vm: SettingsViewModel) {
    Text(
        "The voice Orbit speaks with; picking one plays a sample. Online voices need internet: without it, " +
            "Orbit uses the same voice's offline version.",
        style = MaterialTheme.typography.bodySmall,
    )
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.name == draft.ttsVoice }?.label ?: draft.ttsVoice ?: "Engine default"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Voice", modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = {
                vm.loadVoices() // picks up voices downloaded via "Get more voices"
                open = true
            }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Engine default") }, onClick = {
                    open = false
                    vm.selectVoice(null)
                })
                voices.forEach { voice ->
                    DropdownMenuItem(text = { Text(voice.label) }, onClick = {
                        open = false
                        vm.selectVoice(voice.name)
                    })
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.previewVoice(draft.ttsVoice) }) { Text("Play sample") }
        TextButton(onClick = vm::openTtsSettings) { Text("Get more voices") }
    }
}

@Composable
private fun ListeningSounds(draft: AppSettings, headsets: List<Headset>?, vm: SettingsViewModel) {
    Text(
        "The sound Orbit plays when it starts and stops listening. Alerting carries over wind noise in a " +
            "helmet; silent plays nothing.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EarconStyle.GENTLE, EarconStyle.ALERTING).forEach { style ->
            OutlinedButton(onClick = { vm.previewSounds(style) }) { Text("Play ${style.label.lowercase()}") }
        }
    }
    SoundChoice("Default", draft.defaultSounds, vm::setDefaultSounds)
    when {
        headsets == null -> Text("Grant the nearby devices permission to set sounds per headset.", style = MaterialTheme.typography.bodySmall)
        else -> headsets.forEach { headset ->
            SoundChoice(headset.name, draft.headsetSounds[headset.address] ?: draft.defaultSounds) { vm.setHeadsetSounds(headset, it) }
        }
    }
}

@Composable
private fun SoundChoice(name: String, selected: EarconStyle, onSelect: (EarconStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EarconStyle.entries.forEachIndexed { i, style ->
                SegmentedButton(
                    selected = style == selected,
                    onClick = { onSelect(style) },
                    shape = SegmentedButtonDefaults.itemShape(i, EarconStyle.entries.size),
                ) { Text(style.label) }
            }
        }
    }
}

@Composable
private fun HomeAssistantSettings(draft: AppSettings, status: String?, vm: SettingsViewModel) {
    val credential = draft.homeAssistant
    if (credential != null) {
        val how = if (credential is HaCredential.OAuth) "signed in" else "long-lived token"
        Text("Connected to ${draft.homeAssistantUrl} ($how).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::testHa) { Text("Test") }
            OutlinedButton(onClick = vm::disconnectHa) { Text("Disconnect") }
        }
    } else {
        Text(
            "Lets Orbit control your smart home. Use an address that works wherever you use Orbit (e.g. your remote URL).",
            style = MaterialTheme.typography.bodySmall,
        )
        var url by remember { mutableStateOf(draft.homeAssistantUrl) }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Address") },
            placeholder = { Text("https://home.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.startHaSignIn(url) }) { Text("Sign in") }
        var showToken by remember { mutableStateOf(false) }
        if (!showToken) {
            TextButton(onClick = { showToken = true }) { Text("Use a long-lived access token instead") }
        } else {
            var token by remember { mutableStateOf("") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Long-lived access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Home Assistant → your profile → Security → Long-lived access tokens.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.useHaToken(url, token) }) { Text("Use token") }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
