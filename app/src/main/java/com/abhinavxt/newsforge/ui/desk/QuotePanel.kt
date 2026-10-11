@file:OptIn(ExperimentalLayoutApi::class)

package com.abhinavxt.newsforge.ui.desk

import com.abhinavxt.newsforge.ui.theme.Mono
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import com.abhinavxt.newsforge.ui.components.Delta
import com.abhinavxt.newsforge.ui.components.KeyValue
import com.abhinavxt.newsforge.ui.components.Monogram
import com.abhinavxt.newsforge.ui.components.accentWash
import com.abhinavxt.newsforge.ui.components.leftRail
import com.abhinavxt.newsforge.ui.components.moveColor
import com.abhinavxt.newsforge.ui.theme.NumStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.theme.BrandGradient
import com.abhinavxt.newsforge.ui.theme.Chalk100
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.Ink500
import com.abhinavxt.newsforge.ui.theme.Ink700
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Ink600
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import java.util.Locale

/**
 * The tape, beside the headline.
 *
 * Rendered from a payload pushed by the user's own machine — this app holds no broker
 * credentials and computes none of these numbers. Everything is optional, so the panel
 * shows what arrived and omits the rest rather than displaying placeholders.
 */
@Composable
fun QuotePanel(
    payload: DeskPayload,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    /** Draw the monogram and symbol at the top; off where the screen already names it. */
    showSymbol: Boolean = false,
) {
    val change = payload.changePercent
    // The same up and down colours the feed's ticker chips use, and the panel takes its
    // rail and wash from them: OrderBlock's zone card, where the edge says which way
    // before any number is read. The arrow on the move says it again for anyone who
    // cannot tell the two colours apart.
    val changeColor = change?.let { moveColor(it) } ?: Chalk500
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink700)
            .background(accentWash(changeColor))
            .border(1.dp, if (change != null) changeColor.copy(alpha = 0.3f) else Hairline, shape)
            .leftRail(changeColor)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (showSymbol) {
            Row(
                Modifier.padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Monogram(payload.symbol, changeColor, size = 30.dp)
                Text(
                    text = payload.symbol,
                    style = NumStyle.copy(fontSize = 15.sp, lineHeight = 18.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(payload, nowMillis)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = payload.ltp?.let { "₹" + format(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            change?.let {
                Delta(it, Modifier.padding(start = 10.dp, bottom = 3.dp), style = NumStyle.copy(fontSize = 13.sp))
            }
            Spacer(Modifier.weight(1f))
            if (!showSymbol) StatusPill(payload, nowMillis)
        }

        // Label left, figure right, two to a row — the zone card's grid. The sender's own
        // level names are kept in their natural order: PDH before PDL, not alphabetical.
        val cells = buildList {
            payload.vwap?.let { vwap ->
                add(Triple("VWAP", format(vwap), payload.aboveVwap?.let { if (it) QuoteUp else QuoteDown }))
            }
            payload.relativeVolume?.let { add(Triple("Rel vol", String.format(Locale.US, "%.1fx", it), null)) }
            payload.confluenceScore?.let { score ->
                add(Triple(payload.confluenceVerdict ?: "Conf", "$score%", null))
            }
            val ordered = LEVEL_ORDER.mapNotNull { key -> payload.levels[key]?.let { key to it } }
                .ifEmpty { payload.levels.entries.map { it.key to it.value } }
            for ((key, value) in ordered) add(Triple(key.replace('_', ' '), format(value), null))
        }
        if (cells.isNotEmpty()) {
            Column(Modifier.padding(top = 10.dp)) {
                for (pair in cells.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        for ((label, value, color) in pair) {
                            KeyValue(
                                label = label,
                                value = value,
                                valueColor = color ?: MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        payload.positionInDayRange?.let { position ->
            DayRangeBar(position, changeColor, Modifier.padding(top = 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = payload.dayLow?.let { "L ${format(it)}" }.orEmpty(),
                    style = NumStyle.copy(fontSize = 11.sp),
                    color = Chalk500,
                )
                Text(
                    text = payload.dayHigh?.let { "H ${format(it)}" }.orEmpty(),
                    style = NumStyle.copy(fontSize = 11.sp),
                    color = Chalk500,
                )
            }
        }

        payload.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Age and origin, stated, never implied. "Live" on an exchange quote would be a lie —
 * that data is delayed before it is published — and a price whose age is unknown is
 * labelled rather than quietly shown as current.
 */
@Composable
private fun StatusPill(payload: DeskPayload, nowMillis: Long) {
    val stale = payload.isStale(nowMillis)
    Pill(
        text = when {
            stale -> "Stale"
            payload.isDelayed -> "NSE · delayed"
            else -> "Live"
        },
        color = when {
            stale -> CatRegulatory
            payload.isDelayed -> Chalk500
            // The accent, not the price colours: "live" is a fact about the feed, and
            // drawing it red on a falling stock would read as a warning.
            else -> MaterialTheme.colorScheme.primary
        },
        dot = true,
    )
}

/**
 * Where the price sits between the day's low and high: a track, a lit span up to the
 * price, and a marker on it, so the position reads without looking at either number.
 */
@Composable
private fun DayRangeBar(position: Double, color: Color, modifier: Modifier = Modifier) {
    val fraction = position.toFloat().coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(10.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink500),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = 0.7f)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - 10.dp) * fraction)
                .size(10.dp)
                .clip(CircleShape)
                .background(Chalk100)
                .border(2.dp, color, CircleShape),
        )
    }
}

private val LEVEL_ORDER = listOf("pdh", "pdc", "pdl", "cpr_tc", "cpr_p", "cpr_bc", "r1", "s1")

private fun format(value: Double): String = String.format(Locale.US, "%,.2f", value)
