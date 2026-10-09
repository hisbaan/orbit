package com.hisbaan.orbit.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hisbaan.orbit.R
import com.hisbaan.orbit.assistant.AssistantState
import com.hisbaan.orbit.assistant.Phase
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest

class OverlayActions(
    /** Tap outside the card or back: stop the turn and close. */
    val dismiss: () -> Unit,
    val talk: () -> Unit,
    val ask: (String) -> Unit,
    val stop: () -> Unit,
    /** The user started typing: stop listening so the mic isn't fighting the keyboard. */
    val typing: () -> Unit,
    val openApp: () -> Unit,
    /** A card was tapped: open its link (a web URL, or `app:<package>`). */
    val openLink: (String) -> Unit,
)

private val CardShape = RoundedCornerShape(28.dp)

/**
 * The assistant's pop-up: a scrim over whatever is on screen and a card at the bottom. The
 * card slides up when [visible] turns true and back down when it turns false.
 */
@Composable
fun AssistantOverlay(state: AssistantState, visible: MutableTransitionState<Boolean>, actions: OverlayActions) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close Orbit",
                        onClick = actions.dismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { it / 2 } +
                scaleIn(spring(stiffness = 500f), initialScale = 0.92f) + fadeIn(tween(150)),
            exit = slideOutVertically(tween(180)) { it / 2 } + scaleOut(tween(180), targetScale = 0.95f) + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(12.dp),
        ) {
            Card(state, actions)
        }
    }
}

@Composable
private fun Card(state: AssistantState, actions: OverlayActions) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().activityGlow(state.phase),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(state.phase, actions.openApp)
            if (state.hasContent) Body(state, actions.openLink, Modifier.padding(end = 8.dp))
            InputRow(state.phase, actions)
        }
    }
}

/**
 * A gradient edge that follows the card's rounded corners and turns slowly while Orbit is
 * busy; when idle it settles to a plain hairline. It only animates while busy: the card stays
 * up after a turn, and an endless animation would redraw it every frame for as long as it does.
 */
private fun Modifier.activityGlow(phase: Phase): Modifier = composed {
    val busy = phase != Phase.IDLE
    val colors = MaterialTheme.colorScheme
    val strength by animateFloatAsState(if (busy) 1f else 0f, tween(400), label = "glow")
    val angle = remember { Animatable(0f) }
    val period = if (phase == Phase.THINKING) 1_600 else 3_200
    LaunchedEffect(busy, period) {
        if (!busy) return@LaunchedEffect
        // One turn at a time from wherever it is, so a change of speed doesn't jump.
        while (true) {
            val from = angle.value % FULL_TURN
            angle.snapTo(from)
            angle.animateTo(from + FULL_TURN, tween(period, easing = LinearEasing))
        }
    }
    val gradient = listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)
    val idle = colors.outlineVariant
    drawWithCache {
        val outline = CardShape.createOutline(size, layoutDirection, this)
        val radius = maxOf(size.width, size.height) / 2
        val direction = Offset(cos(angle.value), sin(angle.value)) * radius
        val middle = Offset(size.width / 2, size.height / 2)
        val brush = Brush.linearGradient(gradient, start = middle - direction, end = middle + direction)
        onDrawWithContent {
            drawContent()
            if (strength < 1f) drawOutline(outline, idle, alpha = 1f - strength, style = Stroke(1.dp.toPx()))
            if (strength > 0f) {
                drawOutline(outline, brush, alpha = 0.25f * strength, style = Stroke(6.dp.toPx()))
                drawOutline(outline, brush, alpha = strength, style = Stroke(2.dp.toPx()))
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
            phase.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = openApp) {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = "Open Orbit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Body(state: AssistantState, openLink: (String) -> Unit, modifier: Modifier = Modifier) {
    // Cards can make this tall: cap it and let it scroll, keeping the input row in view.
    Column(modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            // Follow the reply as it streams in: its scroll range grows once each chunk is laid out.
            LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collectLatest { scroll.animateScrollTo(it) } }
            Text(
                reply,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(scroll),
            )
        }
        state.cards.forEach { CardView(it, openLink) }
        val used = state.actions.filterNot { it.startsWith("show_") }.map(::toolLabel).distinct()
        if (used.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                used.forEach { ToolChip(it) }
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ToolChip(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * A text field and one round button: send when there's text, stop while Orbit is busy, and
 * otherwise the mic to talk again.
 */
@Composable
private fun InputRow(phase: Phase, actions: OverlayActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun send() {
        val typed = text.trim()
        if (typed.isEmpty()) return
        text = ""
        focus.clearFocus()
        actions.ask(typed)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (text.isEmpty()) {
                    Text("Ask Orbit", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Ask Orbit" }
                        .onFocusChanged { if (it.isFocused) actions.typing() },
                )
            }
        }
        val busy = phase != Phase.IDLE
        val (icon, description) = when {
            text.isNotBlank() -> R.drawable.ic_send to "Send"
            busy -> R.drawable.ic_stop to "Stop"
            else -> R.drawable.ic_mic to "Talk"
        }
        val container by animateColorAsState(
            if (busy && text.isBlank()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
            label = "button",
        )
        val content by animateColorAsState(
            if (busy && text.isBlank()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
            label = "buttonContent",
        )
        FilledIconButton(
            onClick = {
                when {
                    text.isNotBlank() -> send()
                    busy -> actions.stop()
                    else -> {
                        focus.clearFocus()
                        actions.talk()
                    }
                }
            },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(icon), contentDescription = description)
        }
    }
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

private val AssistantState.hasContent: Boolean
    get() = transcript != null || partialTranscript.isNotBlank() || reply != null || actions.isNotEmpty() || error != null || cards.isNotEmpty()


/** What a tool call looks like to the user. */
private fun toolLabel(tool: String): String = when {
    tool.startsWith("home_") -> "Smart home"
    tool.startsWith("media_") || tool.startsWith("play_") -> "Media"
    else -> when (tool) {
        "call_contact" -> "Phone"
        "current_time" -> "Clock"
        "get_notifications" -> "Notifications"
        "get_weather" -> "Weather"
        "navigate" -> "Maps"
        "open_app" -> "Apps"
        "set_alarm" -> "Alarm"
        "set_timer" -> "Timer"
        else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private const val FULL_TURN = (2 * Math.PI).toFloat()
