package com.abhinavxt.newsforge.ui.desk

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
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
            .clip(RoundedCornerShape(6.dp))
            .background(Ink600)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = payload.ltp?.let { format(it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            change?.let {
                Text(
                    text = String.format(Locale.US, " %+.2f%%", it),
                    style = MaterialTheme.typography.labelMedium,
                    color = changeColor,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Age and origin are stated, never implied. "live" on an exchange quote would
            // be a lie — that data is delayed before it is published — and a price whose
            // age is unknown is labelled rather than quietly shown as current.
            val stale = payload.isStale(nowMillis)
            Text(
                text = when {
                    stale -> "stale"
                    payload.isDelayed -> "NSE · delayed"
                    else -> "live"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (stale) CatRegulatory else Chalk500,
            )
        }

        payload.positionInDayRange?.let { position ->
            DayRangeBar(position, Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = payload.dayLow?.let { format(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Chalk500,
                )
                Text(
                    text = payload.dayHigh?.let { format(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
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
            Text(
                text = chips.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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
                color = Chalk500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        payload.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DayRangeBar(position: Double, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(position.toFloat())
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private val LEVEL_ORDER = listOf("pdh", "pdc", "pdl", "cpr_tc", "cpr_p", "cpr_bc", "r1", "s1")

private fun format(value: Double): String = String.format(Locale.US, "%,.2f", value)
