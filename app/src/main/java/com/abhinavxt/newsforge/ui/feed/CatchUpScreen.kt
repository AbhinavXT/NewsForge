@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.PrimaryButton
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.TonalButton
import com.abhinavxt.newsforge.ui.chart.Sparkline
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Unread stories one at a time, a swipe apart.
 *
 * The feed is built for scanning, and scanning is the wrong mode for the first ten
 * minutes of the morning: there is a known pile to get through, and a list makes it easy
 * to skim past the one that mattered. Here every story gets the whole screen for a
 * moment, and moving on marks it read — so the pile visibly shrinks and nothing is
 * skipped by accident.
 *
 * @param stories the queue as it was when catch-up started, in that order. Fixed for the
 *   session: re-ranking under the reader as stories are read would move the next card.
 */
@Composable
fun CatchUpScreen(
    stories: List<ScoredArticle>,
    nowMillis: Long,
    prices: PriceBook,
    onRead: (ScoredArticle) -> Unit,
    onMarkRead: (ScoredArticle) -> Unit,
    onToggleSave: (ScoredArticle) -> Unit,
    onOpenSymbol: (String) -> Unit,
    onBack: () -> Unit,
) {
    // One page past the last story, for the finish.
    val pager = rememberPagerState { stories.size + 1 }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val position = pager.currentPage.coerceAtMost(stories.size)

    // Leaving a page marks it read: a swipe onward is the decision "I have seen this".
    LaunchedEffect(pager, stories) {
        var previous = pager.currentPage
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            if (page > previous) {
                for (index in previous until page) stories.getOrNull(index)?.let(onMarkRead)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            previous = page
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                ScreenHeader(
                    title = "Catch up",
                    subtitle = if (position < stories.size) {
                        "${position + 1} of ${stories.size}"
                    } else {
                        "Done"
                    },
                    onBack = onBack,
                )
                LinearProgressIndicator(
                    progress = { if (stories.isEmpty()) 1f else position.toFloat() / stories.size },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = Color.Transparent,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = ScreenGutter),
            pageSpacing = 12.dp,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { page ->
            val story = stories.getOrNull(page)
            if (story == null) {
                EmptyState(
                    icon = R.drawable.ic_done_all,
                    title = "All caught up",
                    body = "${stories.size} stories read.",
                    actionLabel = "Back to the feed",
                    onAction = onBack,
                )
            } else {
                CatchUpCard(
                    scored = story,
                    nowMillis = nowMillis,
                    prices = prices,
                    onRead = { onRead(story) },
                    onToggleSave = { onToggleSave(story) },
                    onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
                    onOpenSymbol = onOpenSymbol,
                )
            }
        }
    }
}

@Composable
private fun CatchUpCard(
    scored: ScoredArticle,
    nowMillis: Long,
    prices: PriceBook,
    onRead: () -> Unit,
    onToggleSave: () -> Unit,
    onNext: () -> Unit,
    onOpenSymbol: (String) -> Unit,
) {
    val article = scored.article
    Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        NfCard(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            // The card owns the whole page, and a headline with two lines of summary fills
            // a third of it. Pinning the context to the bottom keeps the story where the eye
            // lands first and puts the evidence beside the buttons that act on it, instead
            // of leaving a blank slab between them. Still scrolls when a long story needs
            // more than the screen.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    StoryHead(article, nowMillis)
                    StoryContext(scored, nowMillis, prices, onOpenSymbol)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = "Read",
                icon = R.drawable.ic_news,
                onClick = onRead,
                modifier = Modifier.weight(1f),
            )
            TonalButton(
                text = if (article.saved) "Saved" else "Save",
                icon = if (article.saved) R.drawable.ic_star else R.drawable.ic_star_border,
                onClick = onToggleSave,
                contentColor = if (article.saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            TonalButton(
                text = "Next",
                icon = R.drawable.ic_chevron_right,
                onClick = onNext,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StoryHead(article: ArticleSummary, nowMillis: Long) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(article.category.label, article.category.accent, dot = true)
            Text(
                text = RelativeTime.byline(
                    article.sourceName,
                    article.otherSources.size,
                    article.publishedAt,
                    nowMillis,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                maxLines = 1,
            )
        }
        Text(
            text = article.title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 31.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        StoryPresentation.summaryOrNull(article)?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        AcrossOutlets(article.clusterId, article.title)
    }
}

/**
 * The same evidence the story sheet gives — why it ranked, what the price did, who else
 * carried it — so deciding whether to open a story does not mean leaving catch-up.
 */
@Composable
private fun StoryContext(
    scored: ScoredArticle,
    nowMillis: Long,
    prices: PriceBook,
    onOpenSymbol: (String) -> Unit,
) {
    val article = scored.article
    Column(Modifier.padding(top = 24.dp)) {
        HorizontalDivider(color = Hairline)

        if (scored.moveProbability != null) {
            LearnedExplainer(scored.moveProbability, scored.drivers)
        } else {
            RankExplainer(article, nowMillis)
        }

        prices.reactionFor(article)?.let { reaction ->
            ReactionBanner(reaction, article.publishedAt, nowMillis)
            Sparkline(
                samples = prices.samples[reaction.symbol].orEmpty(),
                markerAtMillis = article.publishedAt,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (article.symbols.isNotEmpty()) {
            SheetLabel("Companies")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (symbol in article.symbols.take(6)) {
                    val change = prices[symbol]?.changePercent
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenSymbol(symbol) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (change != null) {
                            Text(
                                text = StoryPresentation.changeLabel(change),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (change < 0) QuoteDown else QuoteUp,
                            )
                        }
                    }
                }
            }
        }

        val sectors = article.sectors.mapNotNull { Sector.parse(it) }
        if (sectors.isNotEmpty()) {
            SheetLabel("Sectors")
            Text(
                text = sectors.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Outlets(article)
    }
}
