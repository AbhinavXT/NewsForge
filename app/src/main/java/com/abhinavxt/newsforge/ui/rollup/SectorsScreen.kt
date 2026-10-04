package com.abhinavxt.newsforge.ui.rollup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.ui.components.ChangePill
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.theme.BrandGradient
import com.abhinavxt.newsforge.ui.theme.Chalk500
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.rollup.SectorRollup
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import java.util.Locale

/**
 * @param rollups hottest sector first; empty before the first story is stored.
 * @param market context the sector figures are read against, absent until a quote
 *   response arrives with enough constituents to describe a market.
 */
data class SectorsUiState(
    val rollups: List<SectorRollup> = emptyList(),
    val market: MarketBreadth? = null,
    val nowMillis: Long = 0L,
)

/**
 * Where today's news is, by sector.
 *
 * The pre-open question this answers is not "what happened" — the feed does that — but
 * "which basket is today about", which a chronological list of headlines actively hides:
 * six pharma stories scattered through forty others read as six stories, not as a sector
 * moving.
 *
 * Two numbers per row and they are never merged. News heat says how much is being
 * written; the move says what the tape did. A sector loud in the news and flat on the
 * tape is a different morning from a quiet sector up two per cent, and a combined
 * "attention score" would render them identically.
 */
@Composable
fun SectorsScreen(
    state: SectorsUiState,
    onOpenSector: (Sector) -> Unit,
    onOpenSymbol: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                title = "Sectors",
                subtitle = "${state.rollups.size} sectors in the news",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.rollups.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_chart,
                title = "Nothing to summarise yet",
                body = "Sectors appear once stories tagged with them arrive.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        // Heat is relative to the hottest sector on screen, so the bar answers "how loud
        // compared with the loudest" rather than an absolute nobody has a feel for.
        val hottest = state.rollups.maxOf { it.heat }.takeIf { it > 0 } ?: 1.0

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.market?.let { market ->
                item(key = "market") { MarketCard(market) }
            }
            items(state.rollups, key = { it.sector.name }) { rollup ->
                SectorRow(
                    rollup = rollup,
                    market = state.market,
                    heat = (rollup.heat / hottest).toFloat().coerceIn(0f, 1f),
                    onClick = { onOpenSector(rollup.sector) },
                    onOpenSymbol = onOpenSymbol,
                )
            }
        }
    }
}

/**
 * The market the sector figures are read against: its median move and its breadth as one
 * split bar, advances on the left and declines on the right.
 */
@Composable
private fun MarketCard(market: MarketBreadth) {
    NfCard(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MARKET · ${market.index}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Chalk500,
                )
                Text(
                    text = market.formattedMedian(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = moveColor(market.medianChangePercent),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${market.advances} up",
                    style = MaterialTheme.typography.labelMedium,
                    color = QuoteUp,
                )
                Text(
                    text = "${market.declines} down",
                    style = MaterialTheme.typography.labelMedium,
                    color = QuoteDown,
                )
            }
        }
        val total = market.total.coerceAtLeast(1)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (market.advances > 0) {
                Box(Modifier.weight(market.advances.toFloat() / total).fillMaxHeight().background(QuoteUp))
            }
            if (market.unchanged > 0) {
                Box(Modifier.weight(market.unchanged.toFloat() / total).fillMaxHeight().background(Chalk500))
            }
            if (market.declines > 0) {
                Box(Modifier.weight(market.declines.toFloat() / total).fillMaxHeight().background(QuoteDown))
            }
        }
    }
}

@Composable
private fun SectorRow(
    rollup: SectorRollup,
    market: MarketBreadth?,
    heat: Float,
    onClick: () -> Unit,
    onOpenSymbol: (String) -> Unit,
) {
    NfCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rollup.sector.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Exposure marked, not sorted on. A sector you hold does not become the day's
            // story because you hold it.
            if (rollup.watchlisted) Pill("Watchlist", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            rollup.breadth?.let { breadth ->
                ChangePill(breadth.medianChangePercent)
            }
        }

        // News heat: how much is being written, as a bar. Kept apart from the move above
        // on purpose — loud in the news and flat on the tape is a different morning from
        // quiet and up two per cent.
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(heat.coerceAtLeast(0.04f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(BrandGradient),
                )
            }
            Text(
                text = "${rollup.stories} " + if (rollup.stories == 1) "story" else "stories",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Chalk500,
            )
        }

        val detail = buildString {
            rollup.breadth?.let { breadth ->
                append(breadth.advances)
                append(" up, ")
                append(breadth.declines)
                append(" down of ")
                append(breadth.constituents)
            }
            // Against the market, because the sector figure alone cannot say whether the
            // sector did anything: up 1.2% on a market up 1.1% is up nothing.
            rollup.relativeMove(market)?.let { relative ->
                if (isNotEmpty()) append(" · ")
                append(String.format(Locale.US, "%+.1f", relative))
                append(" pts vs market")
            }
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        rollup.lead?.let { lead ->
            Text(
                text = lead.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (rollup.symbols.isNotEmpty()) {
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Four, because the row is a pointer into the sector rather than a list
                // of it — the names beyond the first few are why the row is tappable.
                for (symbol in rollup.symbols.take(4)) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { onOpenSymbol(symbol) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The same green and red every broker app in the country uses, and the ordinary text
 * colour at exactly zero.
 *
 * Flat is not a small gain. Tinting it would make a sector that did nothing read as one
 * that did something very slightly — and the sign is printed alongside regardless, since
 * red against green is the one pair the palette otherwise avoids leaning on.
 */
@Composable
private fun moveColor(changePercent: Double): Color = when {
    changePercent > 0.0 -> QuoteUp
    changePercent < 0.0 -> QuoteDown
    else -> MaterialTheme.colorScheme.onSurface
}
