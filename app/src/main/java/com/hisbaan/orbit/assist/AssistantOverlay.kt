package com.hisbaan.orbit.assist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hisbaan.orbit.assistant.AssistantState
import com.hisbaan.orbit.assistant.Phase

class OverlayActions(
    /** Tap outside the card or back: stop the turn and close. */
    val dismiss: () -> Unit,
    val talk: () -> Unit,
    val stop: () -> Unit,
    val openApp: () -> Unit,
)

/** The assistant's pop-up: a scrim over whatever is on screen and a card at the bottom. */
@Composable
fun AssistantOverlay(state: AssistantState, actions: OverlayActions) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions.dismiss),
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Column {
                ActivityStrip(state.phase)
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Header(state.phase, actions.openApp)
                    Body(state)
                    Footer(state.phase, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(phase: Phase, openApp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PhaseIndicator(phase)
        Spacer(Modifier.width(10.dp))
        Text(
            phaseLabel(phase),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = openApp) { Text("Orbit") }
    }
}

@Composable
private fun Body(state: AssistantState) {
    val heard = state.transcript ?: state.partialTranscript.takeIf { it.isNotBlank() }
    heard?.let {
        Text(
            it,
            style = MaterialTheme.typography.titleLarge,
            color = if (state.transcript == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    state.reply?.let { reply ->
        val scroll = rememberScrollState()
        // Follow the reply as it streams in.
        LaunchedEffect(reply) { scroll.animateScrollTo(scroll.maxValue) }
        Text(
            reply,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(scroll),
        )
    }
    if (state.actions.isNotEmpty()) {
        Text(
            state.actions.distinct().joinToString(" · ") { it.replace('_', ' ') },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Footer(phase: Phase, actions: OverlayActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (phase == Phase.IDLE) {
            FilledTonalButton(onClick = actions.talk) { Text("Talk") }
        } else {
            OutlinedButton(onClick = actions.stop) { Text("Stop") }
        }
    }
}

/** A thin gradient along the top of the card that drifts while Orbit is busy. */
@Composable
private fun ActivityStrip(phase: Phase) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val shift by rememberInfiniteTransition(label = "strip").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "shift",
    )
    val offset = if (phase == Phase.IDLE) 0f else shift * 600f
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Brush.horizontalGradient(colors, startX = -offset, endX = 1_200f - offset)),
    )
}

@Composable
private fun PhaseIndicator(phase: Phase) {
    when (phase) {
        Phase.STARTING, Phase.THINKING, Phase.FINISHING ->
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Phase.LISTENING, Phase.SPEAKING -> {
            val color = if (phase == Phase.LISTENING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(if (phase == Phase.LISTENING) 600 else 900), RepeatMode.Reverse),
                label = "scale",
            )
            Canvas(Modifier.size(16.dp)) { drawCircle(color, radius = size.minDimension / 2 * pulse) }
        }
        Phase.IDLE -> {
            val color = MaterialTheme.colorScheme.outline
            Canvas(Modifier.size(16.dp)) { drawCircle(color, radius = size.minDimension / 2 * 0.55f) }
        }
    }
}

private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.IDLE -> "Ready"
    Phase.STARTING -> "Connecting…"
    Phase.LISTENING -> "Listening…"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Speaking"
    Phase.FINISHING -> "Finishing…"
}
