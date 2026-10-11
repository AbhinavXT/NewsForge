package com.abhinavxt.newsforge.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.desk.BoardRow
import com.abhinavxt.newsforge.core.desk.BoardSide
import com.abhinavxt.newsforge.core.desk.BoardSnapshot
import com.abhinavxt.newsforge.core.desk.SectorFlow
import com.abhinavxt.newsforge.ui.components.ConvictionDots
import com.abhinavxt.newsforge.ui.components.Delta
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.Eyebrow
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.IndexTag
import com.abhinavxt.newsforge.ui.components.KeyValue
import com.abhinavxt.newsforge.ui.components.Monogram
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SectionTitle
import com.abhinavxt.newsforge.ui.components.SegmentedControl
import com.abhinavxt.newsforge.ui.components.Stat
import com.abhinavxt.newsforge.ui.components.StatStrip
import com.abhinavxt.newsforge.ui.components.cornerBrackets
import com.abhinavxt.newsforge.ui.components.moveColor
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.NumStyle
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * OrderBlock's Liquidity Board, from the desk's own scan, with the news on each name.
 *
 * Bullish and bearish are one control rather than two lists stacked: on a phone the second
 * list is always a scroll away from the first, and the question being asked at 09:20 is
 * "which side, then which names", in that order.
 */
@Composable
fun BoardScreen(
    state: BoardUiState,
    onRefresh: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sideIndex by rememberSaveable { mutableIntStateOf(0) }
    val board = state.board

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                title = "Board",
                subtitle = board?.let { builtLabel(it, state.nowMillis) } ?: "F&O order blocks & liquidity",
                onBack = onBack,
                actions = {
                    if (state.requesting) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else if (state.configured) {
                        IconCircleButton(
                            icon = R.drawable.ic_refresh,
                            contentDescription = "Ask the desk for a fresh board",
                            onClick = onRefresh,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.configured -> EmptyState(
                icon = R.drawable.ic_board,
                title = "The board comes from your desk",
                body = "Connect the TickerForge bridge and the desk will send its F&O " +
                    "order-block scan here.",
                actionLabel = "Set up bridge",
                onAction = onOpenSettings,
                modifier = Modifier.padding(padding),
            )

            board == null -> EmptyState(
                icon = R.drawable.ic_board,
                title = if (state.requesting) "Asking the desk…" else "No board yet",
                body = state.error ?: "The desk builds it from the F&O list. It needs a Kite " +
                    "session on the desk and a TickerForge that knows /board nf.",
                actionLabel = if (state.requesting) null else "Ask the desk",
                onAction = onRefresh,
                modifier = Modifier.padding(padding),
            )

            else -> {
                val side = if (sideIndex == 0) BoardSide.BULL else BoardSide.BEAR
                val rows = board.rows(side)
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    state.error?.let { message ->
                        item(key = "error") {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelMedium,
                                color = CatRegulatory,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ScreenGutter, vertical = 4.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(CatRegulatory.copy(alpha = 0.12f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "breadth") { Breadth(board) }
                    item(key = "side") {
                        SegmentedControl(
                            options = listOf(
                                "▲ Bullish ${board.bull.size}",
                                "▼ Bearish ${board.bear.size}",
                            ),
                            selectedIndex = sideIndex,
                            onSelect = { sideIndex = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenGutter, vertical = 12.dp),
                        )
                    }
                    if (rows.isEmpty()) {
                        item(key = "none") {
                            Text(
                                text = "Nothing ${side.label.lowercase(Locale.US)} on the board. " +
                                    "Choppy names score zero and stay off it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Chalk500,
                                modifier = Modifier.padding(horizontal = ScreenGutter + 2.dp, vertical = 12.dp),
                            )
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> "${row.side}:${row.symbol}" }) { index, row ->
                        BoardRowCard(
                            row = row,
                            lead = index == 0,
                            followed = row.symbol in state.followed,
                            headline = state.headlines[row.symbol],
                            nowMillis = state.nowMillis,
                            onClick = { onOpenSymbol(row.symbol) },
                        )
                    }
                    if (board.sectors.isNotEmpty()) {
                        item(key = "sectors-title") {
                            SectionTitle("Sector flow", count = board.sectors.size)
                        }
                        item(key = "sectors") { SectorFlowCard(board.sectors) }
                    }
                }
            }
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** "Live · 11:42 · 2m ago" or "Last session · 15:29". */
private fun builtLabel(board: BoardSnapshot, nowMillis: Long): String {
    val at = board.takenAtMillis ?: return if (board.live) "Live" else "Last session"
    val clock = Instant.ofEpochMilli(at).atZone(com.abhinavxt.newsforge.core.rank.MarketClock.ZONE).format(CLOCK)
    return if (board.live && !board.stale) {
        "Live · $clock · ${RelativeTime.ago(at, nowMillis)}"
    } else {
        "Last session · $clock"
    }
}

private fun sideColor(side: BoardSide): Color = if (side == BoardSide.BULL) QuoteUp else QuoteDown

/** Bullish, choppy, bearish: three figures and the split as one bar. */
@Composable
private fun Breadth(board: BoardSnapshot) {
    Column(Modifier.padding(horizontal = ScreenGutter)) {
        StatStrip(
            listOf(
                Stat("Bullish", "▲ ${board.bullCount}", color = QuoteUp),
                Stat("Choppy", "${board.choppyCount}"),
                Stat("Bearish", "▼ ${board.bearCount}", color = QuoteDown),
            )
        )
        val total = board.total.coerceAtLeast(1)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((count, color) in listOf(
                board.bullCount to QuoteUp,
                board.choppyCount to Chalk500.copy(alpha = 0.5f),
                board.bearCount to QuoteDown,
            )) {
                if (count > 0) {
                    Box(
                        Modifier
                            .weight(count.toFloat() / total)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                    )
                }
            }
        }
        val leading = board.sectors.firstOrNull()
        val lagging = board.sectors.lastOrNull()?.takeIf { board.sectors.size > 1 }
        if (leading != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LeadLag("Lead", leading, Modifier.weight(1f))
                if (lagging != null) LeadLag("Lag", lagging, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LeadLag(label: String, sector: SectorFlow, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label)
        Text(
            text = sector.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Delta(sector.changePercent, style = NumStyle.copy(fontSize = 11.5.sp))
    }
}

/**
 * One ranked name. The leader on each side carries OrderBlock's brackets and wash in the
 * side's colour; a name you follow keeps the accent rail it has everywhere else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardRowCard(
    row: BoardRow,
    lead: Boolean,
    followed: Boolean,
    headline: BoardHeadline?,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val tone = sideColor(row.side)
    NfCard(
        modifier = Modifier
            .padding(horizontal = ScreenGutter, vertical = 5.dp)
            .then(if (lead) Modifier.cornerBrackets(tone) else Modifier),
        onClick = onClick,
        accent = if (lead) tone else null,
        rail = if (followed) MaterialTheme.colorScheme.primary else null,
        contentPadding = PaddingValues(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Monogram(row.symbol, tone, size = if (lead) 38.dp else 32.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.symbol,
                    style = NumStyle.copy(fontSize = if (lead) 17.sp else 15.sp, lineHeight = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    row.changePercent?.let {
                        Delta(it, style = NumStyle.copy(fontSize = if (lead) 15.sp else 13.sp))
                    }
                    row.ltp?.let {
                        Text(
                            text = "₹" + String.format(Locale.US, "%,.2f", it),
                            style = NumStyle.copy(fontSize = 12.sp),
                            color = Chalk500,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IndexTag(row.rank, color = if (lead) tone else Chalk500)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = row.score.toString(),
                        style = NumStyle.copy(fontSize = 13.sp),
                        fontWeight = FontWeight.Bold,
                        color = tone,
                    )
                    ConvictionDots(row.conviction, tone)
                }
            }
        }

        // The flags that make a name worth looking at now, rather than just strong.
        val flags = buildList<Pair<String, Color>> {
            if (row.reversal) add("REV · at zone" to CatPolicy)
            row.trap?.let { trap ->
                add("Trap ${if (trap.bullish) "▲" else "▼"} ${trap.label}" to if (trap.bullish) QuoteUp else QuoteDown)
            }
            row.oiRead?.let { add(it.label to MaterialTheme.colorScheme.onSurfaceVariant) }
            if (followed) add("Following" to MaterialTheme.colorScheme.primary)
        }
        if (flags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((label, color) in flags) Pill(label, color)
            }
        }

        Column(Modifier.padding(top = 8.dp)) {
            if (row.hasOrderBlock) {
                KeyValue(
                    label = "Order block",
                    value = String.format(Locale.US, "%,.2f–%,.2f", row.orderBlockBottom, row.orderBlockTop),
                    valueColor = tone,
                )
            } else {
                KeyValue("Order block", "none live", valueColor = Chalk500)
            }
            row.orderBlockDistancePercent?.let { distance ->
                KeyValue(
                    label = "To zone",
                    value = if (row.inZone) "in zone" else String.format(Locale.US, "%+.2f%%", distance),
                    valueColor = if (row.inZone) tone else MaterialTheme.colorScheme.onSurface,
                )
            }
            val evidence = listOfNotNull(
                row.moveAtr?.let { String.format(Locale.US, "%.1f ATR", it) },
                row.volumeRatio?.let { String.format(Locale.US, "%.1fx vol", it) },
                row.adx?.let { String.format(Locale.US, "ADX %.0f", it) },
            )
            if (evidence.isNotEmpty()) KeyValue("Why", evidence.joinToString(" · "))
        }

        if (headline != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = headline.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = RelativeTime.format(headline.publishedAt, nowMillis),
                    style = NumStyle.copy(fontSize = 11.sp),
                    color = Chalk500,
                )
            }
        }
    }
}

/**
 * Average move per sector as bars either side of a centre line — TickerForge's flow rows.
 * Scaled to the largest move on screen, so the shape answers "which way, and how far
 * compared with the rest".
 */
@Composable
private fun SectorFlowCard(sectors: List<SectorFlow>) {
    val widest = sectors.maxOf { kotlin.math.abs(it.changePercent) }.takeIf { it > 0 } ?: 1.0
    NfCard(
        modifier = Modifier.padding(horizontal = ScreenGutter),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        for (sector in sectors) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = sector.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(110.dp),
                )
                val share = (kotlin.math.abs(sector.changePercent) / widest).toFloat().coerceIn(0.02f, 1f)
                Row(Modifier.weight(1f).height(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        if (sector.changePercent < 0) {
                            Box(
                                Modifier.fillMaxWidth(share).fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(moveColor(sector.changePercent))
                            )
                        }
                    }
                    Box(Modifier.width(1.dp).height(14.dp).background(Hairline))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        if (sector.changePercent >= 0) {
                            Box(
                                Modifier.fillMaxWidth(share).fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(moveColor(sector.changePercent))
                            )
                        }
                    }
                }
                Delta(sector.changePercent, style = NumStyle.copy(fontSize = 11.5.sp), modifier = Modifier.width(64.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
