@file:OptIn(ExperimentalLayoutApi::class)

package com.abhinavxt.newsforge.ui.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import com.abhinavxt.newsforge.ui.components.ChangePill
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
fun QuotePanel(payload: DeskPayload, nowMillis: Long, modifier: Modifier = Modifier) {
    val change = payload.changePercent
    // The same green and red the feed's ticker chips use. These were the category
    // accents, which meant a falling price was drawn in the colour that means
    // "regulatory" two rows above it.
    val changeColor = when {
        change == null -> MaterialTheme.colorScheme.onSurfaceVariant
        change >= 0 -> QuoteUp
        else -> QuoteDown
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink700)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = payload.ltp?.let { format(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            change?.let {
                ChangePill(it, Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            // Age and origin are stated, never implied. "live" on an exchange quote would
            // be a lie — that data is delayed before it is published — and a price whose
            // age is unknown is labelled rather than quietly shown as current.
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
                    // The accent, not the price colours: "live" is a fact about the feed,
                    // and drawing it red on a falling stock would read as a warning.
                    else -> MaterialTheme.colorScheme.primary
                },
                dot = true,
            )
        }

        payload.positionInDayRange?.let { position ->
            DayRangeBar(position, Modifier.padding(top = 14.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = payload.dayLow?.let { "L ${format(it)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Chalk500,
                )
                Text(
                    text = payload.dayHigh?.let { "H ${format(it)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Chalk500,
                )
            }
        }

        val chips = buildList {
            payload.vwap?.let { add("VWAP ${format(it)}") }
            payload.aboveVwap?.let { add(if (it) "above VWAP" else "below VWAP") }
            payload.relativeVolume?.let { add(String.format(Locale.US, "%.1fx vol", it)) }
            payload.confluenceScore?.let { score ->
                add(payload.confluenceVerdict?.let { "$it $score%" } ?: "conf $score%")
            }
        }
        if (chips.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (chip in chips) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ink600)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (payload.levels.isNotEmpty()) {
            Text(
                // Ordered by the sender's own key names rather than sorted: PDH before PDL
                // reads naturally, alphabetical does not.
                text = LEVEL_ORDER
                    .mapNotNull { key -> payload.levels[key]?.let { "${key.uppercase()} ${format(it)}" } }
                    .joinToString("  ")
                    .ifEmpty { payload.levels.entries.joinToString("  ") { "${it.key.uppercase()} ${format(it.value)}" } },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Chalk500,
                modifier = Modifier.padding(top = 8.dp),
            )
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
 * Where the price sits between the day's low and high: a track, a lit span up to the
 * price, and a marker on it, so the position reads without looking at either number.
 */
@Composable
private fun DayRangeBar(position: Double, modifier: Modifier = Modifier) {
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
                .background(BrandGradient),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - 10.dp) * fraction)
                .size(10.dp)
                .clip(CircleShape)
                .background(Chalk100),
        )
    }
}

private val LEVEL_ORDER = listOf("pdh", "pdc", "pdl", "cpr_tc", "cpr_p", "cpr_bc", "r1", "s1")

private fun format(value: Double): String = String.format(Locale.US, "%,.2f", value)
