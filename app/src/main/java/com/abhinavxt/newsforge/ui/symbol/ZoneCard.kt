package com.abhinavxt.newsforge.ui.symbol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.ta.LiquidityKind
import com.abhinavxt.newsforge.core.ta.ZoneAnalysis
import com.abhinavxt.newsforge.ui.components.Eyebrow
import com.abhinavxt.newsforge.ui.components.KeyValue
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.accentWash
import com.abhinavxt.newsforge.ui.components.leftRail
import com.abhinavxt.newsforge.ui.theme.CatDeal
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.Ink700
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import java.util.Locale

/**
 * OrderBlock's "one clear zone": the block in play, the levels either side of it and the
 * liquidity resting beyond, as label and figure — what the chart above draws, said in
 * numbers you can set an alert at.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZoneCard(zones: ZoneAnalysis, modifier: Modifier = Modifier) {
    val active = zones.active
    val tone = when {
        active == null -> Chalk500
        active.bullish -> QuoteUp
        else -> QuoteDown
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink700)
            .then(if (active != null) Modifier.background(accentWash(tone)) else Modifier)
            .border(1.dp, if (active != null) tone.copy(alpha = 0.3f) else Hairline, shape)
            .leftRail(tone)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Order block & liquidity")
            Spacer(Modifier.weight(1f))
            when {
                active == null -> Pill("No live block", Chalk500)
                active.bullish -> Pill("▲ Bullish zone", QuoteUp)
                else -> Pill("▼ Bearish zone", QuoteDown)
            }
        }

        val flags = buildList<Pair<String, Color>> {
            if (zones.reversal) add("REV · at zone" to CatPolicy)
            zones.recentTrap?.let { t ->
                add("Trap ${if (t.bullish) "▲" else "▼"} ${t.kind.name}" to if (t.bullish) QuoteUp else QuoteDown)
            }
        }
        if (flags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { for ((label, color) in flags) Pill(label, color) }
        }

        Column(Modifier.padding(top = 8.dp)) {
            zones.resistance?.let { KeyValue("Resistance", price(it), valueColor = CatDeal) }
            if (active != null) {
                KeyValue(
                    "Order block",
                    "${price(active.bottom)}–${price(active.top)}",
                    valueColor = tone,
                )
                zones.distancePercent?.let { d ->
                    KeyValue(
                        "To zone",
                        if (d == 0.0) "in zone" else String.format(Locale.US, "%+.2f%%", d),
                        valueColor = if (d == 0.0) tone else MaterialTheme.colorScheme.onSurface,
                    )
                }
                KeyValue("Move", String.format(Locale.US, "%.1f ATR", active.moveAtr))
            }
            zones.support?.let { KeyValue("Support", price(it), valueColor = CatPolicy) }
            // Resting liquidity only — what has not been run yet, nearest first each side.
            for (level in zones.resting) {
                KeyValue(
                    "Liquidity · ${label(level.kind)}",
                    price(level.price) + if (level.touches > 1) " ×${level.touches}" else "",
                )
            }
        }

        if (active == null && zones.resting.isEmpty()) {
            Text(
                text = "No displacement on these bars yet — a zone appears once a bar moves " +
                    "more than 1.5 ATR through a swing.",
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun label(kind: LiquidityKind): String = when (kind) {
    LiquidityKind.EQH -> "equal highs"
    LiquidityKind.EQL -> "equal lows"
    LiquidityKind.PDH -> "prev high"
    LiquidityKind.PDL -> "prev low"
}

private fun price(value: Double): String = String.format(Locale.US, "%,.2f", value)
