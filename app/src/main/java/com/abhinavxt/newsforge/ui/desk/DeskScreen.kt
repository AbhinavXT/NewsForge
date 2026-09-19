@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun DeskScreen(
    state: DeskUiState,
    onSave: (String, String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenLink: (String) -> Unit,
    onClearTest: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var configuring by remember { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Newest first, so the top is where a reply lands. Following it automatically is the
    // difference between a log and a conversation: something sent and answered while the
    // screen is open should not need scrolling to find.
    //
    // Only when the top is already in view. Scrolled back through yesterday's signals,
    // being yanked to the newest every fifteen seconds would make the history unreadable,
    // and the reader who scrolled away is the one who chose to be there.
    LaunchedEffect(state.messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= FOLLOW_THRESHOLD) {
            listState.animateScrollToItem(0)
        }
    }

    // A send is an explicit intent to see what comes back, so it moves regardless of
    // where the list was.
    LaunchedEffect(state.sending) {
        if (state.sending) listState.animateScrollToItem(0)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (state.configured) {
                CommandBar(
                    value = command,
                    sending = state.sending,
                    recent = state.recentCommands,
                    onValueChange = { command = it },
                    onPick = { command = it },
                    onSend = {
                        onSend(command)
                        command = ""
                    },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Desk", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (state.configured) {
                                "${state.messages.size} from ${state.topic}"
                            } else {
                                "Not connected"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.configured) {
                        TextButton(onClick = onMarkAllRead) { Text("Read") }
                        TextButton(onClick = onRefresh) { Text("Refresh") }
                    }
                    TextButton(onClick = { configuring = true; onClearTest() }) { Text("Setup") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.configured) {
                            "Nothing from the desk yet."
                        } else {
                            "Connect your ntfy topic to see alerts from your own machine here.\n\n" +
                                "This is a history, not a replacement for push — keep the ntfy " +
                                "app installed for instant delivery."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.messages, key = { it.id }) { message ->
                        val quotes = message.payloads
                        if (quotes.isNotEmpty()) {
                            // A structured push is data, not prose: rendering it as a
                            // line of JSON would be worse than not sending it.
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                // Capped, because a batch covering a whole watchlist
                                // would otherwise turn one message into a screenful and
                                // bury the strategy signals this log exists for.
                                for (quote in quotes.take(QUOTE_PREVIEW)) {
                                    Text(
                                        text = quote.symbol,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                    QuotePanel(
                                        quote,
                                        state.nowMillis,
                                        Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                if (quotes.size > QUOTE_PREVIEW) {
                                    Text(
                                        text = "+${quotes.size - QUOTE_PREVIEW} more symbols",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Chalk500,
                                    )
                                }
                                Text(
                                    text = RelativeTime.format(message.receivedAtMillis, state.nowMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Chalk500,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        } else {
                            MessageRow(message, state.nowMillis) {
                                message.clickUrl?.let(onOpenLink)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (configuring) {
        SetupDialog(
            state = state,
            onTest = onTest,
            onSave = { server, topic, token, commandTopic ->
                onSave(server, topic, token, commandTopic)
                configuring = false
                onClearTest()
            },
            onDismiss = { configuring = false; onClearTest() },
        )
    }
}

@Composable
private fun MessageRow(message: DeskMessage, nowMillis: Long, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = message.clickUrl != null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message.summary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (message.isHighPriority) FontWeight.Medium else FontWeight.Normal,
            color = if (message.isHighPriority) {
                CatRegulatory
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        // The body is shown in full rather than truncated: these are your own alerts, and
        // they are already as short as you chose to make them.
        if (message.title != null && message.body.isNotBlank()) {
            Text(
                text = message.body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = RelativeTime.format(message.receivedAtMillis, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
            )
            if (message.tags.isNotEmpty()) {
                Text(
                    text = message.tags.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Chalk500,
                )
            }
        }
    }
}

@Composable
private fun SetupDialog(
    state: DeskUiState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var server by remember { mutableStateOf(state.server) }
    var topic by remember { mutableStateOf(state.topic) }
    var token by remember { mutableStateOf(state.token) }
    var commandTopic by remember { mutableStateOf(state.commandTopic) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desk bridge") },
        text = {
            Column {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = commandTopic,
                    onValueChange = { commandTopic = it },
                    label = { Text("Command topic (optional)") },
                    supportingText = {
                        Text(
                            "Leave blank if the desk listens on the same topic it replies on.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                state.testResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.startsWith("OK") || it.startsWith("Connected")) {
                            CatOrder
                        } else {
                            CatRegulatory
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = topic.isNotBlank(),
                onClick = { onSave(server, topic, token, commandTopic) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = topic.isNotBlank(),
                    onClick = { onTest(server, topic, token) },
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Quotes shown per message in the desk log.
 *
 * The log is a record of what the desk said, not a market screen — the feed and the
 * symbol screen are where prices belong. Three is enough to confirm a batch arrived and
 * looks right, which is all this view is for.
 */
private const val QUOTE_PREVIEW = 3

/**
 * A line to the desk.
 *
 * The bridge was read-only, while the desk on the other end has been announcing "remote
 * control online, send /help" into a log this app could only watch. Everything needed to
 * answer it was already here — the topic, the token, the HTTP client — and nothing was
 * pointed at it.
 *
 * No command palette, and no validation of what is typed. The vocabulary belongs to the
 * desk and changes there without this app hearing about it; a list of known commands
 * would be a second place to update and a confident way to be wrong. What is offered
 * instead is what has worked before, which stays right by construction.
 */
@Composable
private fun CommandBar(
    value: String,
    sending: Boolean,
    recent: List<String>,
    onValueChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // The keyboard covers this bar without it. `enableEdgeToEdge` switches the
            // window to drawing behind the system bars, which also stops the manifest's
            // `adjustResize` from resizing anything — so the composer stays where it was
            // and the keyboard is simply drawn over it. Nothing in the manifest fixes
            // that; the inset has to be applied here.
            //
            // After the background, so the surface colour fills the strip the padding
            // opens up rather than leaving the scaffold showing through behind it.
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Only while the field is empty: once there is something to send, a row of past
        // commands is competing with the thing being typed.
        if (recent.isNotEmpty() && value.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (line in recent) {
                    AssistChip(
                        onClick = { onPick(line) },
                        label = {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !sending,
                placeholder = {
                    Text("/help", style = MaterialTheme.typography.bodyMedium)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            TextButton(onClick = onSend, enabled = !sending && value.isNotBlank()) {
                Text(if (sending) "…" else "Send")
            }
        }
    }
}

/**
 * How far down the list the reader can be and still be followed to the newest message.
 *
 * One, not zero: a list resting a few pixels into its first item still counts as being at
 * the top, and requiring an exact zero would leave it stuck after the smallest scroll.
 */
private const val FOLLOW_THRESHOLD = 1
