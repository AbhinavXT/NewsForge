@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.ui.components.ChipStrip
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.QuoteUp
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun DeskScreen(
    state: DeskUiState,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenLink: (String) -> Unit,
    /** The bridge is configured on the Settings tab; this takes the reader there. */
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            ScreenHeader(
                title = "Desk",
                subtitle = if (state.configured) {
                    "${state.messages.size} messages · ${state.topic}"
                } else {
                    "Bridge to your own machine"
                },
                eyebrow = {
                    Pill(
                        text = if (state.configured) "Connected" else "Not connected",
                        color = if (state.configured) QuoteUp else Chalk500,
                        dot = true,
                    )
                },
                actions = {
                    if (state.configured) {
                        IconCircleButton(
                            icon = R.drawable.ic_done_all,
                            contentDescription = "Mark all read",
                            onClick = onMarkAllRead,
                        )
                        IconCircleButton(
                            icon = R.drawable.ic_refresh,
                            contentDescription = "Refresh",
                            onClick = onRefresh,
                        )
                    }
                    IconCircleButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = "Desk settings",
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenGutter, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (state.messages.isEmpty()) {
                if (state.configured) {
                    EmptyState(
                        icon = R.drawable.ic_desk,
                        title = "Nothing from the desk yet",
                        body = "Signals and replies from your machine will land here.",
                    )
                } else {
                    EmptyState(
                        icon = R.drawable.ic_desk,
                        title = "Connect your desk",
                        body = "Connect your ntfy topic to see alerts from your own machine " +
                            "here.\n\nThis is a history, not a replacement for push — keep " +
                            "the ntfy app installed for instant delivery.",
                        actionLabel = "Set up bridge",
                        onAction = onOpenSettings,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = ScreenGutter,
                        end = ScreenGutter,
                        top = 4.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        val quotes = message.payloads
                        if (quotes.isNotEmpty()) {
                            // A structured push is data, not prose: rendering it as a
                            // line of JSON would be worse than not sending it.
                            NfCard(contentPadding = PaddingValues(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "QUOTES · ${quotes.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Chalk500,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = RelativeTime.format(
                                            message.receivedAtMillis,
                                            state.nowMillis,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Chalk500,
                                    )
                                }
                                // Capped, because a batch covering a whole watchlist
                                // would otherwise turn one message into a screenful and
                                // bury the strategy signals this log exists for.
                                for (quote in quotes.take(QUOTE_PREVIEW)) {
                                    Text(
                                        text = quote.symbol,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                                    )
                                    QuotePanel(
                                        quote,
                                        state.nowMillis,
                                        Modifier.padding(bottom = 10.dp),
                                    )
                                }
                                if (quotes.size > QUOTE_PREVIEW) {
                                    Text(
                                        text = "+${quotes.size - QUOTE_PREVIEW} more symbols",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Chalk500,
                                        modifier = Modifier.padding(start = 2.dp),
                                    )
                                }
                            }
                        } else {
                            MessageRow(message, state.nowMillis) {
                                message.clickUrl?.let(onOpenLink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One message from the desk, as a card.
 *
 * High-priority messages get an amber edge and a label. Colouring the whole text amber, as
 * this used to, made an urgent message harder to read than a routine one, which is the
 * wrong way round.
 */
@Composable
private fun MessageRow(message: DeskMessage, nowMillis: Long, onClick: () -> Unit) {
    NfCard(
        onClick = if (message.clickUrl != null) onClick else null,
        border = if (message.isHighPriority) CatRegulatory.copy(alpha = 0.45f) else Hairline,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.isHighPriority) Pill("Priority", CatRegulatory, dot = true)
            Text(
                text = RelativeTime.format(message.receivedAtMillis, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Chalk500,
                modifier = Modifier.weight(1f),
            )
            if (message.clickUrl != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_open),
                    contentDescription = "Has link",
                    tint = Chalk500,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = message.summary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (message.isHighPriority) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        // The body is shown in full rather than truncated: these are your own alerts, and
        // they are already as short as you chose to make them.
        if (message.title != null && message.body.isNotBlank()) {
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (message.tags.isNotEmpty()) {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tag in message.tags) {
                    Pill(tag, Chalk500)
                }
            }
        }
    }
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
            .background(MaterialTheme.colorScheme.background)
            // The keyboard covers this bar without it. `enableEdgeToEdge` switches the
            // window to drawing behind the system bars, which also stops the manifest's
            // `adjustResize` from resizing anything — so the composer stays where it was
            // and the keyboard is simply drawn over it. Nothing in the manifest fixes
            // that; the inset has to be applied here.
            //
            // After the background, so the surface colour fills the strip the padding
            // opens up rather than leaving the scaffold showing through behind it.
            .imePadding()
            .padding(vertical = 6.dp),
    ) {
        // Only while the field is empty: once there is something to send, a row of past
        // commands is competing with the thing being typed.
        if (recent.isNotEmpty() && value.isEmpty()) {
            ChipStrip {
                for (line in recent) {
                    NfChip(
                        label = line,
                        selected = false,
                        monospace = true,
                        onClick = { onPick(line) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !sending,
                shape = RoundedCornerShape(24.dp),
                placeholder = {
                    Text(
                        "/help",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Hairline,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            val canSend = !sending && value.isNotBlank()
            if (sending) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconCircleButton(
                    icon = R.drawable.ic_send,
                    contentDescription = "Send",
                    onClick = onSend,
                    enabled = canSend,
                    size = 48.dp,
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Chalk500
                    },
                    container = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
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
