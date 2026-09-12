@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.calendar

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.calendar.CalendarSection
import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.ui.theme.CatDeal
import com.abhinavxt.newsforge.ui.theme.CatNeutral
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.CatResults
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun CalendarScreen(
    state: CalendarUiState,
    onToggleFollowedOnly: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Calendar", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${state.total} events ahead",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                FilterChip(
                    selected = state.followedOnly,
                    onClick = onToggleFollowedOnly,
                    label = { Text("Mine only", style = MaterialTheme.typography.labelMedium) },
                )
            }

            if (state.sections.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        // Distinguishes an empty calendar from an unreachable source: the
                        // NSE feeds ship disabled, and without this the screen would look
                        // broken rather than unconfigured.
                        text = if (state.nseEnabled) {
                            "Nothing scheduled in the next few weeks."
                        } else {
                            "No dated events yet.\nEnable an NSE feed under Feeds — that is where dates come from."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for (section in state.sections) {
                        item(key = "h-${section.bucket.name}") { SectionHeader(section) }
                        items(section.events, key = { it.id }) { event ->
                            EventRow(
                                event = event,
                                tier = state.tiers[event.symbol],
                                nowMillis = state.nowMillis,
                                onClick = { onSelectSymbol(event.symbol) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: CalendarSection) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.bucket.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = section.events.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
        )
    }
}

@Composable
private fun EventRow(
    event: UpcomingEvent,
    tier: WatchTier?,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = event.symbol,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tier != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                // Exposure is shown here, not just in alerts: the whole point of the
                // calendar for a held position is knowing what you are sitting through.
                tier?.let {
                    Text(
                        text = it.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = event.type.label,
                style = MaterialTheme.typography.labelSmall,
                color = event.type.accent,
            )
            Text(
                text = RelativeTime.dayLabel(event.dateMillis, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private val EventType.accent
    get() = when (this) {
        EventType.RESULTS -> CatResults
        EventType.DIVIDEND -> CatPolicy
        EventType.CORPORATE_ACTION -> CatDeal
        EventType.BOARD_MEETING -> CatNeutral
    }
