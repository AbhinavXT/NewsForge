package com.abhinavxt.newsforge.ui.symbol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.quote.PriceAlertKind
import com.abhinavxt.newsforge.core.quote.PriceAlerts
import com.abhinavxt.newsforge.core.quote.ResultsEvent
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.data.PriceAlertItem
import com.abhinavxt.newsforge.ui.components.GroupDivider
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.SegmentedControl
import com.abhinavxt.newsforge.ui.feed.StoryPresentation
import com.abhinavxt.newsforge.ui.theme.AccentViolet
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The colour a comparison line is drawn in, here and on the chart. */
val CompareColor: Color get() = AccentViolet

/**
 * The compare control under the range chips: a chip to start one, or the two figures
 * being compared and a way to stop.
 */
@Composable
fun CompareRow(
    symbol: String,
    compare: CompareLine?,
    matches: List<SymbolEntry>,
    onSearch: (String) -> Unit,
    onSetCompare: (String?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (compare == null) {
            NfChip(label = "+ Compare", selected = false, onClick = { picking = true })
        } else {
            Legend(symbol, compare.ownPercent, MaterialTheme.colorScheme.onSurface)
            Legend(compare.symbol, compare.otherPercent, CompareColor)
            Spacer(Modifier.weight(1f))
            IconCircleButton(
                icon = R.drawable.ic_close,
                contentDescription = "Stop comparing",
                onClick = { onSetCompare(null) },
                size = 32.dp,
            )
        }
    }
    if (picking) {
        ComparePicker(
            matches = matches,
            onSearch = onSearch,
            onPick = { picked ->
                picking = false
                onSetCompare(picked)
            },
            onDismiss = {
                picking = false
                onSearch("")
            },
        )
    }
}

@Composable
private fun Legend(symbol: String, percent: Double?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(
            Modifier
                .size(width = 12.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(
            text = symbol,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        percent?.let {
            Text(
                text = StoryPresentation.changeLabel(it),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (it < 0) QuoteDown else QuoteUp,
            )
        }
    }
}

@Composable
private fun ComparePicker(
    matches: List<SymbolEntry>,
    onSearch: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Compare with") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    placeholder = { Text("Company or ticker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                for (entry in matches) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(entry.symbol) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(
                            text = entry.symbol,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The price levels set on this company, and a way to add one.
 *
 * Fired alerts stay listed with the price that fired them, so "did my stop go off" is
 * answered here rather than by scrolling back through the shade.
 */
@Composable
fun PriceAlertsCard(
    alerts: List<PriceAlertItem>,
    lastPrice: Double?,
    nowMillis: Long,
    onAdd: (PriceAlertKind, Double) -> Unit,
    onRemove: (Long) -> Unit,
    onRearm: (Long) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    NfCard(
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardLabel("Price alerts", Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("Add") }
        }
        if (alerts.isEmpty()) {
            Text(
                text = "Get a notification when the price crosses a level, or moves sharply in a day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        alerts.forEachIndexed { index, alert ->
            if (index > 0) GroupDivider()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = PriceAlerts.describe(alert.rule.kind, alert.rule.threshold),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (alert.triggeredAt == null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = alert.triggeredAt?.let { at ->
                            "Fired ${RelativeTime.ago(at, nowMillis)}" +
                                (alert.triggeredPrice?.let { " at ${PriceAlerts.rupees(it)}" } ?: "")
                        } ?: "Armed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (alert.triggeredAt == null) MaterialTheme.colorScheme.primary else Chalk500,
                    )
                }
                if (alert.triggeredAt != null) {
                    TextButton(onClick = { onRearm(alert.rule.id) }) { Text("Re-arm") }
                }
                IconCircleButton(
                    icon = R.drawable.ic_close,
                    contentDescription = "Remove alert",
                    onClick = { onRemove(alert.rule.id) },
                    container = Color.Transparent,
                    tint = Chalk500,
                    size = 36.dp,
                )
            }
        }
    }
    if (adding) {
        AddPriceAlertDialog(
            lastPrice = lastPrice,
            onAdd = { kind, value ->
                adding = false
                onAdd(kind, value)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AddPriceAlertDialog(
    lastPrice: Double?,
    onAdd: (PriceAlertKind, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(PriceAlertKind.ABOVE) }
    // Starts at the price for a level and at a round three per cent for a move, so the
    // common case is nudging a number rather than typing one from scratch.
    fun defaultFor(selected: PriceAlertKind): String = when (selected) {
        PriceAlertKind.MOVE -> "3"
        else -> lastPrice?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
    }
    var text by rememberSaveable { mutableStateOf(defaultFor(PriceAlertKind.ABOVE)) }
    val value = text.trim().replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New price alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SegmentedControl(
                    options = listOf("Above", "Below", "Moves"),
                    selectedIndex = kind.ordinal,
                    onSelect = { index ->
                        kind = PriceAlertKind.entries[index]
                        text = defaultFor(kind)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(if (kind == PriceAlertKind.MOVE) "Per cent, either way" else "Price, ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                lastPrice?.let {
                    Text(
                        text = "Last price ${PriceAlerts.rupees(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let { onAdd(kind, it) } }, enabled = value != null) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Each recent results announcement and what the stock did after it.
 *
 * Absent without any results coverage; with coverage but no daily bars, the rows still
 * show and the moves say so, because the list of dates alone is worth having.
 */
@Composable
fun ResultsHistoryCard(events: List<ResultsEvent>) {
    if (events.isEmpty()) return
    NfCard(
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardLabel("Results reaction", Modifier.weight(1f))
            ColumnHeading("Day")
            ColumnHeading("Week")
        }
        events.forEachIndexed { index, event ->
            if (index > 0) GroupDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = DATE.format(Instant.ofEpochMilli(event.publishedAt).atZone(MarketClock.ZONE)) +
                            if (event.storyCount > 1) " · ${event.storyCount} stories" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                    )
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MoveCell(event.dayMovePercent)
                MoveCell(event.weekMovePercent)
            }
        }
        if (events.all { it.dayMovePercent == null }) {
            Text(
                text = "Reactions fill in once daily price bars after each result have arrived.",
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ColumnHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Chalk500,
        modifier = Modifier.width(MOVE_CELL),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
private fun MoveCell(percent: Double?) {
    Text(
        text = percent?.let { StoryPresentation.changeLabel(it) } ?: "—",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = when {
            percent == null -> Chalk500
            percent < 0 -> QuoteDown
            else -> QuoteUp
        },
        modifier = Modifier.width(MOVE_CELL),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
private fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Chalk500,
        modifier = modifier,
    )
}

private val MOVE_CELL = 58.dp

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
