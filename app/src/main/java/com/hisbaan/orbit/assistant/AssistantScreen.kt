package com.hisbaan.orbit.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SetupStatus(
    val missingPermissions: List<String>,
    val isDefaultAssistant: Boolean,
    val providerConfigured: Boolean,
    val hasMediaAccess: Boolean,
    /** Keys and sign-ins to enter again, e.g. after moving to a new phone ("API key"). */
    val secretsToReenter: List<String> = emptyList(),
) {
    val complete: Boolean
        get() = missingPermissions.isEmpty() && isDefaultAssistant && providerConfigured && hasMediaAccess && secretsToReenter.isEmpty()
}

class AssistantActions(
    val talk: () -> Unit,
    val stop: () -> Unit,
    val grantPermissions: () -> Unit,
    val openAssistantSettings: () -> Unit,
    val openMediaAccessSettings: () -> Unit,
    val openSettings: () -> Unit,
    val openMicLab: () -> Unit,
)

@Composable
fun AssistantScreen(
    state: AssistantState,
    setup: SetupStatus,
    actions: AssistantActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Orbit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.openMicLab) { Text("Mic Lab") }
            TextButton(onClick = actions.openSettings) { Text("Settings") }
        }

        if (!setup.complete) SetupCard(setup, actions)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.phase.label, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            val heard = state.transcript ?: state.partialTranscript.takeIf { it.isNotBlank() }
            heard?.let { Labeled("You", it) }
            state.reply?.let { Labeled("Orbit", it) }
            if (state.actions.isNotEmpty()) Labeled("Tools", state.actions.joinToString())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val active = state.phase != Phase.IDLE
        Button(
            onClick = if (active) actions.stop else actions.talk,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Text(if (active) "Stop" else "Talk", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SetupCard(setup: SetupStatus, actions: AssistantActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            if (setup.missingPermissions.isNotEmpty()) {
                Text("Permissions needed: ${setup.missingPermissions.joinToString()}")
                FilledTonalButton(onClick = actions.grantPermissions) { Text("Grant permissions") }
            }
            if (!setup.isDefaultAssistant) {
                Text("Make Orbit your digital assistant so the headset button and power-button press reach it.")
                OutlinedButton(onClick = actions.openAssistantSettings) { Text("Assistant settings") }
            }
            if (!setup.hasMediaAccess) {
                Text("Allow notification access so Orbit can control music directly (play, shuffle, what's playing) and read your notifications to you.")
                OutlinedButton(onClick = actions.openMediaAccessSettings) { Text("Notification access") }
            }
            if (setup.secretsToReenter.isNotEmpty()) {
                Text("Enter again: ${setup.secretsToReenter.joinToString()}. Keys and sign-ins stay on the phone they were entered on, so a backup restores everything but them.")
                OutlinedButton(onClick = actions.openSettings) { Text("Open settings") }
            } else if (!setup.providerConfigured) {
                Text("Add an LLM provider and model.")
                OutlinedButton(onClick = actions.openSettings) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

