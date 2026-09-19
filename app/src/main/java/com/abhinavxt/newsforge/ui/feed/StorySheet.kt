@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.RankInput
import com.abhinavxt.newsforge.core.rank.Ranker
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.mute.MuteKind
import com.abhinavxt.newsforge.core.quote.Reaction
import com.abhinavxt.newsforge.core.quote.ReactionBasis
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.ui.chart.Sparkline
import com.abhinavxt.newsforge.ui.desk.QuotePanel
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * Detail for one story, without leaving the app.
 *
 * The reason this exists: triaging forty headlines by opening each in a browser is slow,
 * and most of them you only need the first two lines of to decide. The sheet answers "is
 * this worth reading" in one tap; Open is there for when the answer is yes.
 *
 * This is the one screen where density is the wrong goal. The feed above it is packed on
 * purpose, so a headline arrives clipped to two lines with its outlets collapsed to a
 * count — and every question that raises is the question this sheet answers. It gets the
 * full headline, every outlet named, and the exposure control the feed deliberately hides
 * behind a long press.
 */
@Composable
fun StorySheet(
    article: ArticleSummary,
    nowMillis: Long,
    /** What you set by hand. The chips write here and nowhere else. */
    manualTiers: Map<String, WatchTier>,
    /** What your open positions say, from the last desk snapshot. Read-only. */
    deskTiers: Map<String, WatchTier>,
    /**
     * Fresh quotes from the desk, by symbol.
     *
     * The feed shows only the percentage move, because a chip in a dense list has room
     * for one number. This is the screen you open to ask whether the headline matters,
     * and "down 2%" answers far less of that than the same move read against the day's
     * range and VWAP — so here it gets the full panel the symbol screen already uses.
     */
    prices: PriceBook,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onToggleSave: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onSetTier: (String, WatchTier?) -> Unit,
    onSelectSector: (Sector) -> Unit,
    onMute: (MuteRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Scrollable, because the length here is not under our control: a story on five
        // outlets touching four companies is three times the height of a bare filing.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // A dot, matching the section headings above. The bar shape now means
            // "watchlist" everywhere else in the app and should not mean a second thing
            // here.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(article.category.accent)
                )
                Text(
                    text = article.category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = article.category.accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = RelativeTime.format(article.publishedAt, nowMillis),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Chalk500,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Unclipped, unlike the card it came from. A headline truncated at two lines
            // is the single most common reason for opening this at all.
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )

            StoryPresentation.summaryOrNull(article)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            RankExplainer(article, nowMillis)

            prices.reactionFor(article)?.let { reaction ->
                ReactionBanner(reaction, article.publishedAt, nowMillis)
                // The shape behind the number. Drawn for the same symbol the banner is
                // about — a chart of a different company sitting under the figure would
                // be read as the figure's evidence.
                Sparkline(
                    samples = prices.samples[reaction.symbol].orEmpty(),
                    markerAtMillis = article.publishedAt,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }

            Outlets(article)

            // Exposure is set explicitly here rather than by cycling a long-press.
            // It changes how loudly the app interrupts you, so guessing at it from a
            // gesture is the wrong trade — three visible states, one tap each.
            for (symbol in article.symbols) {
                SymbolExposure(
                    symbol = symbol,
                    current = manualTiers[symbol],
                    fromDesk = deskTiers[symbol],
                    quote = prices[symbol],
                    nowMillis = nowMillis,
                    onSelectSymbol = { onSelectSymbol(symbol) },
                    onSetTier = onSetTier,
                )
            }

            // Sectors, when the story touches a basket rather than one name. Often the
            // only tags a policy story has, since it may name no company at all.
            val sectors = article.sectors.mapNotNull { Sector.parse(it) }
            if (sectors.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (sector in sectors) {
                        FilterChip(
                            selected = false,
                            onClick = { onSelectSector(sector) },
                            label = {
                                Text(sector.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }

            // Three equal buttons rather than a row of captions. Four text buttons in a
            // line ran off a narrow phone, and Open — the only one you came here to press
            // if the answer was yes — looked the same as the rest.
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                ) { Text("Open") }
                Button(
                    onClick = onToggleSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(if (article.saved) "Unsave" else "Save") }
                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("Share") }
            }

            // Below the fold of the actions and visually quieter: muting changes what
            // arrives tomorrow, so it should not sit at the same weight as Open. Still
            // next to the byline it silences, so the thing being muted is on screen when
            // the decision is made.
            TextButton(
                onClick = { onMute(MuteRule(MuteKind.SOURCE, article.sourceName)) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = "Mute ${article.sourceName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk500,
                )
            }
        }
    }
}

/**
 * Every outlet carrying the story, named.
 *
 * The card collapses these to "+3", which is the right call in a list and useless once
 * you are deciding whether to trust it. Four outlets on a story is corroboration; four
 * outlets that are all aggregators of the same wire copy is not, and you can only tell
 * by reading the names.
 */
@Composable
private fun Outlets(article: ArticleSummary) {
    val outlets = StoryPresentation.outlets(article)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            text = if (outlets.size > 1) {
                "CARRIED BY ${outlets.size} OUTLETS"
            } else {
                "SOURCE"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
        )
        for (outlet in outlets) {
            Text(
                text = outlet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * One company on the story, and how loudly it is allowed to interrupt you.
 *
 * The chips are bound to [current] — what you set — rather than to the effective tier,
 * which is the stronger of that and [fromDesk]. Binding them to the effective tier would
 * show Leveraged selected on a name you never touched, and tapping it to clear would do
 * nothing visible, because the position holding it is not yours to clear from here.
 *
 * What the desk contributed is stated instead, in a line you cannot press. Selling closes
 * it; the phone does not get a vote.
 */
@Composable
private fun SymbolExposure(
    symbol: String,
    current: WatchTier?,
    fromDesk: WatchTier?,
    quote: DeskPayload?,
    nowMillis: Long,
    onSelectSymbol: () -> Unit,
    onSetTier: (String, WatchTier?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(
            onClick = onSelectSymbol,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (current != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // Above the exposure controls, not below: the price is what you are here to read,
        // and the tier is what you might change afterwards as a result of reading it.
        if (quote != null) {
            QuotePanel(quote, nowMillis, Modifier.padding(top = 2.dp, bottom = 8.dp))
        }
        if (fromDesk != null) {
            Text(
                text = "${fromDesk.label.lowercase()} — from your open positions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (tier in WatchTier.entries) {
                FilterChip(
                    selected = current == tier,
                    // Tapping the active tier clears it, so removing a symbol never
                    // needs a separate control.
                    onClick = { onSetTier(symbol, if (current == tier) null else tier) },
                    label = { Text(tier.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

/**
 * What the market did about this, stated in a sentence rather than a symbol.
 *
 * The card has room for "+2.1% since" and no more. Here there is room to say which
 * company moved, over how long, and — where the basis is the previous close — to be
 * explicit that nobody watched it happen, so the number is context rather than evidence
 * that this headline caused anything.
 */
@Composable
private fun ReactionBanner(reaction: Reaction, publishedAt: Long, nowMillis: Long) {
    val rising = reaction.percent >= 0
    val elapsed = RelativeTime.format(publishedAt, nowMillis)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = StoryPresentation.changeLabel(reaction.percent),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = if (rising) QuoteUp else QuoteDown,
        )
        Text(
            text = when (reaction.basis) {
                ReactionBasis.PUBLICATION ->
                    "${reaction.symbol} since this was published, $elapsed ago"
                ReactionBasis.PREVIOUS_CLOSE ->
                    "${reaction.symbol} on the previous close — no price recorded when " +
                        "this was published"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Why this story is where it is.
 *
 * Ordering is the one claim the app makes and never justifies, and the number of
 * invisible inputs has only grown — a tier that comes from open positions, a weight that
 * shifts it, a half-life that changes with the session. Without this, a top item that
 * looks wrong is indistinguishable from a bug, and there is no way to tell which weight
 * to reach for.
 *
 * Collapsed by default. Most of the time the order is unremarkable and a row of arithmetic
 * under every headline would be clutter; the question only gets asked when something looks
 * out of place, and then it deserves a full answer rather than a hint.
 */
@Composable
private fun RankExplainer(article: ArticleSummary, nowMillis: Long) {
    var expanded by remember(article.id) { mutableStateOf(false) }
    val explanation = remember(article.id, nowMillis) {
        Ranker.explain(
            RankInput(
                category = article.category,
                tier = article.tier,
                publishedAtMillis = article.publishedAt,
                symbolCount = article.symbols.size,
                clusterSize = article.clusterSize,
            ),
            nowMillis = nowMillis,
            phase = MarketClock.phase(nowMillis),
        )
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = explanation.dominant
                    ?.let { "Ranked on ${it.name.lowercase()} · ${it.detail}" }
                    ?: "Why this ranking",
                style = MaterialTheme.typography.labelMedium,
                color = Chalk500,
            )
        }
        if (!expanded) return@Column

        for (factor in explanation.factors) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = factor.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(84.dp),
                )
                Text(
                    text = factor.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = Chalk500,
                    modifier = Modifier.weight(1f),
                )
                // The factor, not a share of the total: "0.5x" says age halved it, which
                // is what a reader wants to know and what points at the weight to change.
                Text(
                    text = String.format(Locale.US, "%.2fx", factor.multiplier),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        factor.isNeutral -> Chalk500
                        factor.multiplier > 1.0 -> QuoteUp
                        else -> QuoteDown
                    },
                )
            }
        }
    }
}
