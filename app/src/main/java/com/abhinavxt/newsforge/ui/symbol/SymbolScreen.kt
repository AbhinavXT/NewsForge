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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.abhinavxt.newsforge.core.quote.PriceSummary
import com.abhinavxt.newsforge.core.quote.TargetConsensus
import com.abhinavxt.newsforge.ui.chart.ChartRange
import com.abhinavxt.newsforge.ui.chart.formatPrice
import com.abhinavxt.newsforge.ui.chart.formatVolume
import com.abhinavxt.newsforge.ui.chart.MacdPane
import com.abhinavxt.newsforge.ui.chart.PriceChart
import com.abhinavxt.newsforge.ui.chart.RangeBar
import com.abhinavxt.newsforge.ui.chart.ResearchSkeleton
import com.abhinavxt.newsforge.ui.chart.RsiPane
import com.abhinavxt.newsforge.ui.chart.VolumePane
import com.abhinavxt.newsforge.ui.desk.QuotePanel
import com.abhinavxt.newsforge.ui.feed.StoryCard
import com.abhinavxt.newsforge.ui.feed.accent
import com.abhinavxt.newsforge.ui.theme.Accent
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
            chart.candles.isEmpty() && !chart.awaiting
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
                // Nothing yet and something on the way: show the shape of what is coming
                // rather than a price header with no price above a chart saying there is
                // no history. Both of those are true for about ten seconds and read as
                // permanent.
                if (chart.awaiting && chart.candles.isEmpty() && chart.price == null) {
                    RangeChips(chart.range, onSetRange)
                    ResearchSkeleton()
                } else {
                    // Only where the header above has nothing to show. QuotePanel already
                    // renders a live quote in full — price, day range, VWAP, levels — so
                    // this is for the case that used to render nothing at all: a company
                    // outside the watchlist, where no quote is fetched and the only price
                    // the app holds is the close of the newest bar it has.
                    if (state.quote == null) PriceHeader(chart.price, state.nowMillis)
                    RelativeStrength(state)
                    ChartSection(chart, onSetRange)
                }
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
                        prices = chart.prices,
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

        // Under the tiers, because it qualifies them. A chip says what kind of exposure
        // this is; the figure says whether it is a position that can move the account or
        // one bought to have a reason to pay attention — and every story below reads
        // differently depending on which. The desk has been sending it all along.
        state.positionWeight?.let { weight ->
            Text(
                text = String.format(java.util.Locale.US, "%.1f%% of book", weight),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.padding(top = 6.dp),
            )
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
        RangeChips(chart.range, onSetRange)

        PriceChart(
            candles = chart.candles,
            storyTimes = chart.storyTimes,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp),
        )

        // Drawn before the empty-chart notice, because it does not need candles: with no
        // bars at all the exchange's own figures still fill it in, and a screen that can
        // show something useful should not lead with an apology.
        chart.yearRange?.let { RangeBar("52-week range", it) }
        chart.targets?.let { TargetStrip(it, chart.price?.last) }

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

        // Above the oscillators: it is the only one of the three that says anything about
        // why a move happened rather than how far it has gone.
        chart.volume?.let { VolumePane(it) }

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

/**
 * Price, change and the day's numbers, directly under the title.
 *
 * The screen used to open with a chart and no price on it, because the only price it knew
 * how to show came from a quote and a quote only exists for followed names. The number a
 * reader wants first was the one thing missing.
 *
 * Laid out as one large line and a quiet grid, rather than an even row of six figures.
 * Price and change are what gets read on arrival; open, high, low and volume are what gets
 * read second, if at all, and giving them equal weight makes the first pair harder to find
 * rather than the second pair easier.
 */
@Composable
private fun PriceHeader(price: PriceSummary?, nowMillis: Long) {
    if (price == null) return
    val up = (price.change ?: 0.0) >= 0
    val tint = if (up) QuoteUp else QuoteDown

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "₹${formatPrice(price.last)}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.width(10.dp))
            price.changePercent?.let { percent ->
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "%+.2f (%+.2f%%)",
                        price.change ?: 0.0,
                        percent,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = tint,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        // Said plainly, because the difference matters and no styling conveys it. A
        // six-month-old chart with a live price and a six-month-old chart with Thursday's
        // close look identical, and only one of them is worth acting on.
        Text(
            text = when (price.source) {
                // Relative for a live price — "2m ago" is what tells you whether to trust
                // it. A day label for a close, because "18 Sep" is the fact and "3 days
                // ago" makes the reader do the subtraction to get back to it.
                PriceSummary.Source.LIVE ->
                    "Live · ${RelativeTime.format(price.asOfMillis ?: nowMillis, nowMillis)}"
                PriceSummary.Source.LAST_CLOSE ->
                    "Last close · ${RelativeTime.dayLabel(price.asOfMillis ?: nowMillis, nowMillis)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            modifier = Modifier.padding(top = 2.dp),
        )

        val cells = buildList {
            price.open?.let { add("Open" to formatPrice(it)) }
            price.high?.let { add("High" to formatPrice(it)) }
            price.low?.let { add("Low" to formatPrice(it)) }
            price.vwap?.let { add("VWAP" to formatPrice(it)) }
            price.volume?.let { add("Volume" to formatVolume(it)) }
        }
        if (cells.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // Three to a row: four is too tight for a five-digit price on a narrow phone,
            // and two wastes half the width on the common case.
            for (row in cells.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    for (cell in row) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = cell.first,
                                style = MaterialTheme.typography.labelSmall,
                                color = Chalk500,
                            )
                            Text(text = cell.second, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // Keeps a short final row aligned with the one above instead of
                    // spreading two cells across the full width.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The range selector, shared by the chart and the skeleton that stands in for it.
 *
 * Live during the wait on purpose. The ranges are known before any data is, and a reader
 * who opens on 1M and wants 1Y should not have to wait for the first to arrive before
 * asking for the second.
 */
@Composable
private fun RangeChips(selected: ChartRange, onSetRange: (ChartRange) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (range in ChartRange.entries) {
            FilterChip(
                selected = selected == range,
                onClick = { onSetRange(range) },
                label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * Where the brokers covering this name think it should trade.
 *
 * A band rather than a number, because the number is read out of a headline and could be
 * wrong. Shown with the count, so a consensus of two reads as the thin evidence it is,
 * and with the gap to the current price, which is the only part anyone acts on.
 *
 * Placed under the 52-week bar on purpose: one says where the price has been, the other
 * where somebody is paid to say it is going, and reading them together is the point.
 */
@Composable
private fun TargetStrip(consensus: TargetConsensus, last: Double?) {
    val gap = last?.takeIf { it > 0 }?.let { (consensus.median - it) / it * 100.0 }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Broker targets · ${consensus.count} note" +
                    if (consensus.count == 1) "" else "s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = gap?.let { String.format(java.util.Locale.US, "%+.1f%%", it) } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    gap == null -> Chalk500
                    gap >= 0 -> QuoteUp
                    else -> QuoteDown
                },
            )
        }
        Text(
            text = "${formatPrice(consensus.low)} – ${formatPrice(consensus.high)} " +
                "· median ${formatPrice(consensus.median)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Said out loud, because nothing else on this screen is guessed from prose and a
        // reader should know which one is.
        Text(
            text = "Read from headlines — treat as approximate",
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
        )
    }
}
