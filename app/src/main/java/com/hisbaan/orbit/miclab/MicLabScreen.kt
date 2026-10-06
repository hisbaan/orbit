package com.hisbaan.orbit.miclab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisbaan.orbit.audio.CaptureSource
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.audio.FocusMode
import com.hisbaan.orbit.audio.PlaybackUsage
import com.hisbaan.orbit.audio.RouteStrategy
import com.hisbaan.orbit.audio.describe

class MicLabActions(
    val back: () -> Unit,
    val requestPermissions: () -> Unit,
    val openAssistantSettings: () -> Unit,
    val copyLog: () -> Unit,
    val shareLog: () -> Unit,
)

@Composable
fun MicLabScreen(
    state: MicLabState,
    log: List<EventLog.Entry>,
    vm: MicLabViewModel,
    actions: MicLabActions,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mic Lab", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.back) { Text("Done") }
                }
            }
            item { SetupCard(state, actions) }
            item { HailCard(state, vm) }
            item { DevicesCard(state, vm) }
            item { RoutingCard(state, vm) }
            item { CaptureCard(state, vm) }
            item { PlaybackCard(state, vm) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Event log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.copyLog) { Text("Copy") }
                    TextButton(onClick = actions.shareLog) { Text("Share") }
                    TextButton(onClick = vm::clearLog) { Text("Clear") }
                }
            }
            items(log.asReversed(), key = { it.seq }) { entry ->
                Text(
                    EventLog.format(entry),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SetupCard(state: MicLabState, actions: MicLabActions) {
    Section("Setup") {
        if (state.missingPermissions.isNotEmpty()) {
            Text("Missing: ${state.missingPermissions.joinToString { it.substringAfterLast('.') }}")
            Button(onClick = actions.requestPermissions) { Text("Grant permissions") }
        } else {
            Text("Permissions granted")
        }
        Text(if (state.isDefaultAssistant) "Orbit is the default assistant" else "Orbit is NOT the default assistant")
        OutlinedButton(onClick = actions.openAssistantSettings) { Text("Assistant settings") }
    }
}

@Composable
private fun HailCard(state: MicLabState, vm: MicLabViewModel) {
    Section("Hail test") {
        Text(
            "Route → beep → record 5s → play back → release, using the settings below. " +
                "Headset triggers run it instead of the assistant when enabled in Settings → Debug.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { vm.runHailTest("in-app button") }, enabled = state.busy == null) { Text("Run hail test") }
        state.busy?.let { Text("Busy: $it", color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun DevicesCard(state: MicLabState, vm: MicLabViewModel) {
    val d = state.devices
    Section("Devices", action = { TextButton(onClick = vm::refresh) { Text("Refresh") } }) {
        Field("Audio mode", d.audioMode)
        Field("Communication device", d.communicationDevice)
        Field("HFP devices", d.hfpDevices.joinToString("\n").ifEmpty { "none" })
        Field("Comm. candidates", d.communicationCandidates.joinToString("\n").ifEmpty { "none" })
        Field("Inputs", d.inputs.joinToString("\n") { it.describe() }.ifEmpty { "none" })
        Field("Outputs", d.outputs.joinToString("\n").ifEmpty { "none" })
    }
}

@Composable
private fun RoutingCard(state: MicLabState, vm: MicLabViewModel) {
    Section("Routing") {
        Picker("Strategy", RouteStrategy.entries, state.settings.strategy, { it.label }) { v ->
            vm.updateSettings { it.copy(strategy = v) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Set MODE_IN_COMMUNICATION", modifier = Modifier.weight(1f))
            Switch(
                checked = state.settings.setCommunicationMode,
                onCheckedChange = { checked -> vm.updateSettings { it.copy(setCommunicationMode = checked) } },
            )
        }
        Picker("Audio focus", FocusMode.entries, state.settings.focusMode, { it.label }) { v ->
            vm.updateSettings { it.copy(focusMode = v) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep focus until HFP link is down", modifier = Modifier.weight(1f))
            Switch(
                checked = state.settings.releaseFocusAfterLinkDown,
                onCheckedChange = { checked -> vm.updateSettings { it.copy(releaseFocusAfterLinkDown = checked) } },
            )
        }
        FilledTonalButton(onClick = vm::toggleRoute, enabled = state.busy == null || state.busy == "route") {
            Text(if (state.route == null) "Acquire route" else "Release route")
        }
        state.route?.let { Field("Active route", it) }
    }
}

@Composable
private fun CaptureCard(state: MicLabState, vm: MicLabViewModel) {
    Section("Capture") {
        Picker("Source", CaptureSource.entries, state.settings.source, { it.label }) { v ->
            vm.updateSettings { it.copy(source = v) }
        }
        val inputOptions = listOf<Int?>(null) + state.devices.inputs.map { it.id }
        Picker(
            "Preferred input",
            inputOptions,
            state.settings.preferredInputId,
            { id -> id?.let { state.devices.inputs.firstOrNull { d -> d.id == it }?.describe() } ?: "Auto" },
        ) { v -> vm.updateSettings { it.copy(preferredInputId = v) } }
        Button(onClick = vm::toggleRecording, enabled = state.busy == null) {
            Text(if (state.recording) "Stop" else "Record (max 30s)")
        }
        LevelMeter(state.rmsDbfs, state.peakDbfs)
        state.lastRecording?.let { Field("Last recording", it) }
    }
}

@Composable
private fun PlaybackCard(state: MicLabState, vm: MicLabViewModel) {
    Section("Playback") {
        Picker("Usage", PlaybackUsage.entries, state.settings.usage, { it.label }) { v ->
            vm.updateSettings { it.copy(usage = v) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = vm::playRecording, enabled = state.busy == null && !state.recording) {
                Text("Play recording")
            }
            OutlinedButton(onClick = vm::playBeep, enabled = state.busy == null) { Text("Beep") }
        }
        OutlinedTextField(
            value = state.ttsText,
            onValueChange = vm::setTtsText,
            label = { Text("TTS phrase") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = vm::speak, enabled = state.busy == null) { Text("Speak") }
    }
}

@Composable
private fun LevelMeter(rmsDbfs: Float, peakDbfs: Float) {
    // Map -60..0 dBFS onto the bar.
    fun fraction(db: Float) = if (db.isFinite()) ((db + 60f) / 60f).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { fraction(rmsDbfs) }, modifier = Modifier.fillMaxWidth())
        Text(
            "RMS ${if (rmsDbfs.isFinite()) "%.1f".format(rmsDbfs) else "—"} dBFS · " +
                "peak ${if (peakDbfs.isFinite()) "%.1f".format(peakDbfs) else "—"} dBFS",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { expanded = true }) { Text(optionLabel(selected)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
