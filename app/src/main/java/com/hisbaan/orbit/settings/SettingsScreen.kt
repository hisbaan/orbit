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
import com.hisbaan.orbit.homeassistant.HaCredential

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
