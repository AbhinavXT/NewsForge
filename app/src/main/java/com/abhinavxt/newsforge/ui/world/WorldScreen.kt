@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.components.ChipStrip
import com.abhinavxt.newsforge.ui.components.Dot
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SectionTitle
import com.abhinavxt.newsforge.ui.components.StorySkeleton
import com.abhinavxt.newsforge.ui.feed.SearchField
import com.abhinavxt.newsforge.ui.feed.SwipeableStoryCard
import com.abhinavxt.newsforge.ui.feed.accent

/**
 * General news: politics, science, international and the rest, apart from the markets.
 *
 * Laid out as a front page rather than a feed. Unfiltered, the most important few
 * stories lead and each topic follows with its best handful, so a quick look covers every
 * subject; a topic's "See all" narrows to it. Once anything is narrowed the sections go
 * and the list is flat — the reader has already said what they want, and headings between
 * three items would only get in the way.
 *
 * A tap opens the story in the reader. The market feed's sheet exists to triage tickers,
 * tiers and price moves, none of which a world story has, so here it would be a stop on
 * the way to the article with nothing on it.
 */
@Composable
fun WorldScreen(
    state: WorldUiState,
    onOpenStory: (ScoredArticle) -> Unit,
    onToggleRead: (ScoredArticle) -> Unit,
    onToggleSave: (ScoredArticle) -> Unit,
    onSelectTopic: (Category?) -> Unit,
    onToggleUnread: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleFollowing: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    customise: WorldCustomiseActions,
    modifier: Modifier = Modifier,
) {
    val ready = state as? WorldUiState.Ready
    var searching by rememberSaveable { mutableStateOf(false) }
    var customising by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                title = "World",
                subtitle = ready?.let { "${it.stories.size} stories" },
                actions = {
                    IconCircleButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = "Customise World",
                        onClick = { customising = true },
                    )
                    IconCircleButton(
                        icon = if (searching) R.drawable.ic_close else R.drawable.ic_search,
                        contentDescription = if (searching) "Close search" else "Search",
                        onClick = {
                            searching = !searching
                            if (!searching) onQueryChange("")
                        },
                        tint = if (searching) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        container = if (searching) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ready != null) {
                AnimatedVisibility(
                    visible = searching,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SearchField(
                        query = ready.filter.query,
                        onQueryChange = onQueryChange,
                        placeholder = "Search world headlines and outlets",
                    )
                }
                TopicRow(
                    counts = ready.topicCounts,
                    followingCount = ready.following.size,
                    hasKeywords = ready.settings.keywords.isNotEmpty(),
                    filter = ready.filter,
                    onSelectTopic = onSelectTopic,
                    onToggleUnread = onToggleUnread,
                    onToggleSaved = onToggleSaved,
                    onToggleFollowing = onToggleFollowing,
                )
                ready.lastError?.let { ErrorBanner(it) }
            }

            when {
                ready == null -> Column(Modifier.padding(top = 8.dp)) {
                    repeat(5) { StorySkeleton() }
                }

                else -> PullToRefreshBox(
                    isRefreshing = ready.refreshing,
                    onRefresh = {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        onRefresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val card: @Composable (ScoredArticle, Boolean, Boolean) -> Unit =
                        { scored, lead, showTopic ->
                            SwipeableStoryCard(
                                modifier = Modifier.padding(
                                    horizontal = ScreenGutter,
                                    vertical = 5.dp,
                                ),
                                article = scored.article,
                                nowMillis = ready.nowMillis,
                                watchlist = emptySet(),
                                onOpen = { onOpenStory(scored) },
                                onToggleSave = { onToggleSave(scored) },
                                onToggleRead = { onToggleRead(scored) },
                                onSelectSymbol = {},
                                onToggleSymbol = {},
                                lead = lead,
                                showCategory = showTopic,
                            )
                        }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    ) {
                        when {
                            ready.stories.isEmpty() && ready.following.isEmpty() -> item {
                                WorldEmptyState(
                                    refreshing = ready.refreshing,
                                    narrowed = ready.filter.isNarrowed,
                                    onClearFilters = onClearFilters,
                                    onRefresh = onRefresh,
                                    modifier = Modifier.fillParentMaxSize(),
                                )
                            }

                            ready.filter.isNarrowed -> items(
                                ready.stories,
                                key = { it.article.clusterId },
                            ) { card(it, false, ready.filter.topic == null) }

                            else -> frontPage(
                                stories = ready.stories,
                                following = ready.following.map { it.first },
                                topics = ready.settings.visibleTopics,
                                card = card,
                                onSelectTopic = onSelectTopic,
                                onShowFollowing = onToggleFollowing,
                            )
                        }
                    }
                }
            }
        }
    }

    CustomiseHost(
        visible = customising,
        ready = ready,
        actions = customise,
        onDismiss = { customising = false },
    )
}

@Composable
private fun CustomiseHost(
    visible: Boolean,
    ready: WorldUiState.Ready?,
    actions: WorldCustomiseActions,
    onDismiss: () -> Unit,
) {
    if (visible && ready != null) {
        WorldCustomiseSheet(settings = ready.settings, actions = actions, onDismiss = onDismiss)
    }
}

/**
 * The unfiltered layout: what you follow, a short lead, then each topic's best few.
 *
 * Following comes first because it is the one section the reader asked for by name. Each
 * story appears once: the lead skips what Following showed and the topics skip both.
 * Topics with nothing in the window are skipped rather than shown empty — a heading over
 * no stories reads as a broken feed, and the Feeds tab is where that should be noticed.
 *
 * @param topics visible topics in the reader's order.
 */
private fun LazyListScope.frontPage(
    stories: List<ScoredArticle>,
    following: List<ScoredArticle>,
    topics: List<Category>,
    card: @Composable (ScoredArticle, Boolean, Boolean) -> Unit,
    onSelectTopic: (Category?) -> Unit,
    onShowFollowing: () -> Unit,
) {
    val shown = HashSet<String>()
    if (following.isNotEmpty()) {
        item(key = "following-header") {
            SectionTitle(
                title = "Following",
                count = following.size,
                trailing = if (following.size > PER_TOPIC) {
                    { TextButton(onClick = onShowFollowing) { Text("See all") } }
                } else {
                    null
                },
            )
        }
        val top = following.take(PER_TOPIC)
        top.mapTo(shown) { it.article.clusterId }
        items(top, key = { "following-${it.article.clusterId}" }) { card(it, false, true) }
    }

    val lead = stories.filterNot { it.article.clusterId in shown }.take(LEAD_COUNT)
    if (lead.isNotEmpty()) {
        item(key = "lead-header") { SectionTitle("Top stories") }
        items(lead, key = { "lead-${it.article.clusterId}" }) { card(it, it === lead.first(), true) }
    }
    lead.mapTo(shown) { it.article.clusterId }

    val byTopic = stories
        .filterNot { it.article.clusterId in shown }
        .groupBy { it.article.category }

    for (topic in topics) {
        val section = byTopic[topic].orEmpty()
        if (section.isEmpty()) continue
        item(key = "header-${topic.name}") {
            SectionTitle(
                title = topic.label,
                count = section.size,
                accent = topic.accent,
                trailing = if (section.size > PER_TOPIC) {
                    { TextButton(onClick = { onSelectTopic(topic) }) { Text("See all") } }
                } else {
                    null
                },
            )
        }
        items(section.take(PER_TOPIC), key = { it.article.clusterId }) { card(it, false, false) }
    }
}

/**
 * @param counts visible topics, in the reader's order, with their story counts.
 */
@Composable
private fun TopicRow(
    counts: Map<Category, Int>,
    followingCount: Int,
    hasKeywords: Boolean,
    filter: WorldFilter,
    onSelectTopic: (Category?) -> Unit,
    onToggleUnread: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleFollowing: () -> Unit,
) {
    ChipStrip {
        NfChip("All", selected = filter.topic == null, onClick = { onSelectTopic(null) })
        // Only once something is followed: a chip that can never have anything under it
        // is a chip to explain.
        if (hasKeywords) {
            NfChip(
                label = "Following",
                selected = filter.followingOnly,
                onClick = onToggleFollowing,
                count = followingCount,
            )
        }
        NfChip("Unread", selected = filter.unreadOnly, onClick = onToggleUnread)
        NfChip("Saved", selected = filter.savedOnly, onClick = onToggleSaved)
        for (topic in counts.keys) {
            val count = counts[topic] ?: 0
            // A topic with nothing in it is hidden unless it is the one selected, so
            // clearing it is always possible from where it was chosen.
            if (count == 0 && filter.topic != topic) continue
            NfChip(
                label = topic.label,
                selected = filter.topic == topic,
                onClick = { onSelectTopic(topic) },
                count = count,
                accent = topic.accent,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(MaterialTheme.colorScheme.error)
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun WorldEmptyState(
    refreshing: Boolean,
    narrowed: Boolean,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        refreshing -> EmptyState(
            icon = R.drawable.ic_refresh,
            title = "Fetching the news",
            body = "World feeds are being read for the first time.",
            modifier = modifier,
        )

        narrowed -> EmptyState(
            icon = R.drawable.ic_search,
            title = "Nothing matches",
            body = "No world stories fit these filters.",
            actionLabel = "Clear filters",
            onAction = onClearFilters,
            modifier = modifier,
        )

        else -> EmptyState(
            icon = R.drawable.ic_globe,
            title = "No world news yet",
            body = "Pull to refresh, or check the world feeds on the Feeds tab.",
            actionLabel = "Refresh",
            onAction = onRefresh,
            modifier = modifier,
        )
    }
}

/** Enough to cover the day's biggest stories without becoming a second feed. */
private const val LEAD_COUNT = 3

/** A topic's best few on the front page; the rest are a "See all" away. */
private const val PER_TOPIC = 3
