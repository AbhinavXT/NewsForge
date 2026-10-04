@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.ui.components.ChipStrip
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SectionTitle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            ScreenHeader(
                title = "Calendar",
                subtitle = "${state.total} events ahead",
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ChipStrip {
                NfChip(
                    label = "All companies",
                    selected = !state.followedOnly,
                    onClick = { if (state.followedOnly) onToggleFollowedOnly() },
                )
                NfChip(
                    label = "Mine only",
                    selected = state.followedOnly,
                    onClick = { if (!state.followedOnly) onToggleFollowedOnly() },
                )
            }

            if (state.sections.isEmpty()) {
                // Distinguishes an empty calendar from an unreachable source: the NSE
                // feeds ship disabled, and without this the screen would look broken
                // rather than unconfigured.
                if (state.nseEnabled) {
                    EmptyState(
                        icon = R.drawable.ic_calendar,
                        title = "Nothing scheduled",
                        body = "No dated events in the next few weeks.",
                    )
                } else {
                    EmptyState(
                        icon = R.drawable.ic_calendar,
                        title = "No dated events yet",
                        body = "Enable an NSE feed under Feeds — that is where dates come from.",
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    for (section in state.sections) {
                        item(key = "h-${section.bucket.name}") { SectionHeader(section) }
                        items(section.events, key = { it.id }) { event ->
                            EventRow(
                                event = event,
                                tier = state.tiers[event.symbol],
                                nowMillis = state.nowMillis,
                                onClick = { onSelectSymbol(event.symbol) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: CalendarSection) {
    SectionTitle(title = section.bucket.label, count = section.events.size)
}

/**
 * One dated event: a date tile, the company, what is happening.
 *
 * The tile leads because a calendar is scanned by date first; the company comes second
 * and the event type third, which is the order the questions get asked in — when, who,
 * what.
 */
@Composable
private fun EventRow(
    event: UpcomingEvent,
    tier: WatchTier?,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val date = Instant.ofEpochMilli(event.dateMillis).atZone(MarketClock.ZONE)
    NfCard(
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp),
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .width(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(event.type.accent.copy(alpha = 0.14f))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = date.format(MONTH).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = event.type.accent,
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = event.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (tier != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    // Exposure is shown here, not just in alerts: the whole point of the
                    // calendar for a held position is knowing what you are sitting through.
                    tier?.let { Pill(it.label, MaterialTheme.colorScheme.primary) }
                }
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill(event.type.label, event.type.accent)
                    Text(
                        text = RelativeTime.dayLabel(event.dateMillis, nowMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                    )
                }
            }
        }
    }
}

private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US)

private val EventType.accent
    get() = when (this) {
        EventType.RESULTS -> CatResults
        EventType.DIVIDEND -> CatPolicy
        EventType.CORPORATE_ACTION -> CatDeal
        EventType.BOARD_MEETING -> CatNeutral
    }
