@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.symbol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.chart.ChartRange
import com.abhinavxt.newsforge.ui.chart.MacdPane
import com.abhinavxt.newsforge.ui.chart.PriceChart
import com.abhinavxt.newsforge.ui.chart.RangeBar
import com.abhinavxt.newsforge.ui.chart.RsiPane
import com.abhinavxt.newsforge.ui.desk.QuotePanel
import com.abhinavxt.newsforge.ui.feed.StoryCard
import com.abhinavxt.newsforge.ui.feed.accent
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun SymbolScreen(
    state: SymbolUiState,
    chart: SymbolChartState,
    onSetRange: (ChartRange) -> Unit,
    onSetTier: (WatchTier?) -> Unit,
    onOpenStory: (ScoredArticle) -> Unit,
    onToggleSave: (ScoredArticle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.symbol, style = MaterialTheme.typography.titleMedium)
                        state.summary?.let {
                            Text(
                                text = "${it.storyCount} stories stored",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // The chart counts as content. A company tapped from a filing may have no stored
        // coverage at all and still be exactly what the reader came to look at, so the
        // empty state only applies when there is nothing of any kind to show.
        if (state.loaded && state.summary?.storyCount == 0 && state.events.isEmpty() &&
            chart.candles.isEmpty()
        ) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing stored for ${state.symbol} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Header(state, onSetTier)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                RelativeStrength(state)
                ChartSection(chart, onSetRange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            for (section in state.sections) {
                item(key = "h-${section.label}") {
                    Text(
                        text = section.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(section.stories, key = { it.article.clusterId }) { scored ->
                    StoryCard(
                        article = scored.article,
                        nowMillis = state.nowMillis,
                        watchlist = state.tier?.let { setOf(state.symbol) }.orEmpty(),
                        onOpen = { onOpenStory(scored) },
                        onToggleSave = { onToggleSave(scored) },
                        // Symbols are inert here: you are already looking at this company,
                        // so navigating to it again would be a no-op that looks broken.
                        onSelectSymbol = {},
                        onToggleSymbol = {},
                        // Grouped by date here, not by kind, so nothing above the card
                        // says what sort of story it is.
                        showCategory = true,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Header(state: SymbolUiState, onSetTier: (WatchTier?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {

        // The tape, when the desk has pushed one. Above everything else: it is the
        // only thing here that is true right now.
        state.quote?.let {
            QuotePanel(it, state.nowMillis, Modifier.padding(bottom = 10.dp))
        }

        state.summary?.nextEvent?.let { event ->
            // The next date leads. It is the one thing on this screen that is about the
            // future, and it is what decides whether holding over tonight is a decision.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${event.type.label}: ${event.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = RelativeTime.dayLabel(event.dateMillis, state.nowMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tier in WatchTier.entries) {
                FilterChip(
                    selected = state.tier == tier,
                    onClick = { onSetTier(if (state.tier == tier) null else tier) },
                    label = { Text(tier.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        val counts = state.summary?.byCategory.orEmpty()
        if (counts.isNotEmpty()) {
            // The point of the screen: four regulatory items in a quarter reads very
            // differently from four headlines met a fortnight apart.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((category, count) in counts) {
                    Text(
                        text = "$count ${category.label.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = category.accent,
                    )
                }
            }
        }

        if (state.events.size > 1) {
            Text(
                text = "${state.events.size} dated events on file",
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Chart, levels and indicators, in the order they get read.
 *
 * One scroller with the timeline rather than a tab beside it. The whole point of this
 * screen is that the price and the coverage are the same story, and a tab makes the
 * reader hold one in their head while looking at the other.
 */
@Composable
private fun ChartSection(
    chart: SymbolChartState,
    onSetRange: (ChartRange) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (range in ChartRange.entries) {
                FilterChip(
                    selected = chart.range == range,
                    onClick = { onSetRange(range) },
                    label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        PriceChart(
            candles = chart.candles,
            storyTimes = chart.storyTimes,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp),
        )

        // Drawn before the empty-chart notice, because it does not need candles: with no
        // bars at all the exchange's own figures still fill it in, and a screen that can
        // show something useful should not lead with an apology.
        chart.yearRange?.let { RangeBar("52-week range", it) }

        if (chart.candles.isEmpty()) {
            // Said once, plainly. The desk is the only source of bars, and a reader whose
            // bridge is not set up would otherwise be left staring at an empty frame
            // wondering whether it is loading.
            Text(
                text = "Price history comes from the desk. Ask it for candles from the Desk tab, " +
                    "or check the bridge is set up.",
                style = MaterialTheme.typography.bodySmall,
                color = Chalk500,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return
        }

        RsiPane(chart.rsi)
        MacdPane(chart.macd)

        chart.mfi.lastOrNull { it != null }?.let { mfi ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "MFI 14",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", mfi),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * The stock's move, and the market's, on one line.
 *
 * The number a reader arrives with is "it is up four per cent". The number that answers
 * whether the story mattered is "and everything else is up three". Without the second,
 * every rising-tide day reads as a company-specific event, which is the single most
 * common way a news feed misleads.
 *
 * Drawn only when both halves are there. Half of a comparison is worse than none — a lone
 * percentage under a heading about the market invites the reader to supply the missing
 * side themselves.
 */
@Composable
private fun RelativeStrength(state: SymbolUiState) {
    val breadth = state.breadth ?: return
    val change = state.quote?.changePercent ?: return
    val relative = breadth.relativeTo(change) ?: return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "vs ${breadth.index}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "market ${breadth.formattedMedian()} · " +
                    "${breadth.advances} up, ${breadth.declines} down",
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
            )
        }
        Text(
            // Percentage points, said out loud. The gap between a 4% move and a 3% one is
            // one point, and writing it as a percentage would be defensible arithmetic
            // and unreadable in context.
            text = String.format(java.util.Locale.US, "%+.2f pp", relative),
            style = MaterialTheme.typography.titleSmall,
            color = if (relative >= 0) QuoteUp else QuoteDown,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
