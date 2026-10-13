package com.hisbaan.orbit.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisbaan.orbit.R
import com.hisbaan.orbit.agent.Card
import kotlin.math.roundToInt

/** How many cards are animating their own size, e.g. a list of sources opening. */
class CardResizes {
    private var count by mutableIntStateOf(0)
    val running: Boolean get() = count > 0

    /** Counts this card in while [animating], so what holds the cards can hold off its own animation. */
    @Composable
    fun Track(animating: Boolean) {
        if (animating) DisposableEffect(Unit) {
            count++
            onDispose { count-- }
        }
    }
}

/** A card the assistant chose to show (see [Card]). Tapping one with a link, or a source, opens it. */
@Composable
fun CardView(card: Card, openLink: (String) -> Unit, resizes: CardResizes) {
    val link = (card as? Card.Weather)?.link
    Surface(
        onClick = { link?.let(openLink) },
        enabled = link != null,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (card) {
                is Card.Weather -> WeatherCard(card)
                is Card.Info -> InfoCard(card)
                is Card.Sources -> SourcesCard(card, openLink, resizes)
            }
        }
    }
}

@Composable
private fun WeatherCard(card: Card.Weather) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(skyEmoji(card.sky), fontSize = 44.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(degrees(card.temperature), style = MaterialTheme.typography.displaySmall)
            Text(
                listOfNotNull(card.condition, card.feelsLike?.let { "feels ${degrees(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(card.place, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.high != null || card.low != null) {
                Text(
                    "H ${degrees(card.high)}  L ${degrees(card.low)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (card.hours.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            card.hours.forEach { hour ->
                Column(Modifier.widthIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hour.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(skyEmoji(hour.sky), fontSize = 20.sp)
                    Text(degrees(hour.temperature), style = MaterialTheme.typography.bodyMedium)
                    Chance(hour.precipitationChance)
                }
            }
        }
    }
    if (card.days.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.days.forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Column(Modifier.width(40.dp), horizontalAlignment = Alignment.End) { Chance(day.precipitationChance) }
                    Text(skyEmoji(day.sky), fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    Text(
                        "${degrees(day.low)} / ${degrees(day.high)}",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(76.dp),
                    )
                }
            }
        }
    }
}

/** A chance of rain worth noticing (10% or more), else nothing. */
@Composable
private fun Chance(percent: Int?) {
    if (percent != null && percent >= 10) {
        Text("$percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoCard(card: Card.Info) {
    Column {
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (card.rows.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.rows.forEach { row ->
                Row {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(row.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Folded to the query by default: the reply already names the site, so the list is there to check. */
@Composable
private fun SourcesCard(card: Card.Sources, openLink: (String) -> Unit, resizes: CardResizes) {
    var open by rememberSaveable(card) { mutableStateOf(false) }
    val shown = remember(card) { MutableTransitionState(open) }
    shown.targetState = open
    resizes.Track(animating = !shown.isIdle)
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    // One child of the card's column, so the gap above the list comes and goes with it.
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sources", style = MaterialTheme.typography.titleMedium)
                Text(card.query, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = if (open) "Hide sources" else "Show sources",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn),
            )
        }
        // Grows itself, so what's below it (tool chips, the input row) slides down with it.
        AnimatedVisibility(shown, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 12.dp)) {
                card.sources.forEach { source ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openLink(source.url) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(source.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(source.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** "11°": temperatures in the card's unit, shown without it. */
private fun degrees(value: Double?): String = value?.let { "${it.roundToInt()}°" } ?: "–"

private fun skyEmoji(sky: String): String = when (sky) {
    "clear" -> "☀️"
    "clear_night" -> "🌙"
    "partly_cloudy" -> "⛅"
    "partly_cloudy_night" -> "☁️"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "drizzle" -> "🌦️"
    "rain" -> "🌧️"
    "snow" -> "❄️"
    "sleet" -> "🌨️"
    "thunderstorm" -> "⛈️"
    "wind" -> "💨"
    else -> "🌡️"
}
