package com.hisbaan.orbit.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class HistoryActions(
    val back: () -> Unit,
    /** Asks the user to authenticate, then loads. */
    val unlock: () -> Unit,
    val select: (String?) -> Unit,
    val delete: (String) -> Unit,
    val copy: (StoredConversation) -> Unit,
    val share: (StoredConversation) -> Unit,
)

@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier = Modifier) {
    val selected = state.conversations.firstOrNull { it.id == state.selected }
    BackHandler(enabled = selected != null) { actions.select(null) }
    if (selected != null) {
        ConversationDetail(selected, actions, modifier)
    } else {
        ConversationList(state, actions, modifier)
    }
}

@Composable
private fun ConversationList(state: HistoryUiState, actions: HistoryActions, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conversation history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.back) { Text("Done") }
        }
        Text(
            "What Orbit heard, each model and tool call, and what it said and did. Kept for 7 days, encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Decrypting…")
            }
            !state.unlocked -> Button(onClick = actions.unlock) { Text("Unlock") }
            state.conversations.isEmpty() -> Text("Nothing yet.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.incomplete) {
                    item { Text("Older conversations didn't load in time. Leave and open the history again to see them.", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.conversations, key = { it.id }) { conversation -> ConversationCard(conversation) { actions.select(conversation.id) } }
            }
        }
    }
}

@Composable
private fun ConversationCard(conversation: StoredConversation, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(HistoryText.dateTime(conversation.startedAt), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (conversation.hasProblem) {
                    Text("Something went wrong", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                conversation.firstWords?.let { "“$it”" } ?: "Nothing heard",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val turns = conversation.turns.size
            Text(
                "$turns ${if (turns == 1) "turn" else "turns"} · ${conversation.toolCalls} tool ${if (conversation.toolCalls == 1) "call" else "calls"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConversationDetail(conversation: StoredConversation, actions: HistoryActions, modifier: Modifier) {
    val turns = remember(conversation) { conversation.turns.map { it to HistoryText.rows(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.select(null) }) { Text("Back") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { actions.copy(conversation) }) { Text("Copy") }
                TextButton(onClick = { actions.share(conversation) }) { Text("Share") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
        Text(HistoryText.dateTime(conversation.startedAt), style = MaterialTheme.typography.titleLarge)
        Text("Tap an entry for its details.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            turns.forEachIndexed { i, (turn, rows) ->
                item(key = "turn-$i") {
                    Text(
                        "Turn ${i + 1} · ${HistoryText.time(turn.startedAt)} · ${turn.source}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(rows.size, key = { j -> "row-$i-$j" }) { j -> EventRow(rows[j]) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.delete(conversation.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EventRow(row: HistoryRow) {
    var open by rememberSaveable { mutableStateOf(false) }
    val color = if (row.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clickable(enabled = row.details != null) { open = !open }.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(HistoryText.time(row.at), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(row.label, style = MaterialTheme.typography.labelLarge, color = color)
        }
        Text(row.summary, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        when {
            row.details == null -> Unit
            open -> SelectionContainer {
                Text(
                    row.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            else -> Text("Details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
