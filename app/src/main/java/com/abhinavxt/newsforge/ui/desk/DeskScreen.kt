@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.desk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenLink: (String) -> Unit,
    onClearTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var configuring by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id }) { message ->
                        val payload = message.payload
                        if (payload != null) {
                            // A structured push is data, not prose: rendering it as a
                            // line of JSON would be worse than not sending it.
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    text = payload.symbol,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                                QuotePanel(payload, state.nowMillis)
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
            onSave = { server, topic, token ->
                onSave(server, topic, token)
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
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var server by remember { mutableStateOf(state.server) }
    var topic by remember { mutableStateOf(state.topic) }
    var token by remember { mutableStateOf(state.token) }

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
                onClick = { onSave(server, topic, token) },
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
