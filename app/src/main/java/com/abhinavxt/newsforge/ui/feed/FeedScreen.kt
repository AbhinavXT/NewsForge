@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun FeedScreen(
    state: FeedUiState,
    onOpenStory: (ScoredArticle) -> Unit,
    onShareStory: (ScoredArticle) -> Unit,
    onMarkRead: (ScoredArticle) -> Unit,
    onToggleSave: (ScoredArticle) -> Unit,
    onSelectSymbol: (String?) -> Unit,
    onToggleSymbol: (String) -> Unit,
    onSetTier: (String, WatchTier?) -> Unit,
    onSelectSector: (Sector?) -> Unit,
    onMute: (MuteRule) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSaved: () -> Unit,
    onToggleUnread: () -> Unit,
    onClearFilters: () -> Unit,
    onSelectGroup: (CategoryGroup?) -> Unit,
    onToggleWatchlist: () -> Unit,
    onSetMode: (FeedMode) -> Unit,
    onRefresh: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? FeedUiState.Ready
    var searching by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var detail by remember { mutableStateOf<ScoredArticle?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (ready?.mode) {
                                FeedMode.BRIEF -> "Overnight brief"
                                else -> "Live feed"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (ready != null) {
                            Text(
                                text = "${ready.stories.size} stories",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = {
                        searching = !searching
                        if (!searching) onQueryChange("")
                    }) { Text(if (searching) "Close" else "Search") }
                    TextButton(onClick = {
                        onSetMode(
                            if (ready?.mode == FeedMode.BRIEF) FeedMode.LIVE else FeedMode.BRIEF
                        )
                    }) {
                        Text(if (ready?.mode == FeedMode.BRIEF) "Live" else "Brief")
                    }
                    TextButton(onClick = onRefresh, enabled = ready?.refreshing != true) {
                        Text(if (ready?.refreshing == true) "…" else "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ready != null) {
                if (searching) {
                    OutlinedTextField(
                        value = ready.filter.query,
                        onValueChange = onQueryChange,
                        label = { Text("Search headlines and tickers") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                ready.filter.sector?.let { sector ->
                    ActiveFilterBanner(
                        label = sector.label,
                        action = null,
                        onAction = {},
                        onClear = { onSelectSector(null) },
                    )
                }
                ready.filter.symbol?.let { symbol ->
                    ActiveFilterBanner(
                        label = symbol,
                        action = "Timeline",
                        onAction = { onOpenSymbol(symbol) },
                        onClear = { onSelectSymbol(null) },
                    )
                }
                FilterRow(
                    counts = ready.chipCounts,
                    filter = ready.filter,
                    watchlistEmpty = ready.watchlist.isEmpty(),
                    onSelectGroup = onSelectGroup,
                    onToggleWatchlist = onToggleWatchlist,
                    onToggleSaved = onToggleSaved,
                    onToggleUnread = onToggleUnread,
                )
                ready.lastError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }

            when {
                state is FeedUiState.Loading -> CenteredMessage("Loading…")

                state is FeedUiState.Error -> CenteredMessage(state.message)

                ready != null && ready.stories.isEmpty() -> EmptyState(
                    refreshing = ready.refreshing,
                    narrowed = ready.filter.isNarrowed,
                    onClearFilters = onClearFilters,
                )

                ready != null -> {
                    val card: @Composable (ScoredArticle) -> Unit = { scored ->
                        StoryCard(
                            article = scored.article,
                            nowMillis = ready.nowMillis,
                            watchlist = ready.watchlist,
                            // Tap opens the sheet, not the browser: most headlines
                            // need only two lines to triage, and a browser round trip
                            // for each of forty is the slow path.
                            onOpen = { detail = scored; onMarkRead(scored) },
                            onToggleSave = { onToggleSave(scored) },
                            onSelectSymbol = onSelectSymbol,
                            onToggleSymbol = onToggleSymbol,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Sections only in the brief, and only when nothing is filtered.
                    // Sectioning a filtered list fights the filter: the reader has
                    // already said what they want, and grouping the remainder by kind
                    // just puts headers between three items.
                    val sectioned = ready.mode == FeedMode.BRIEF && !ready.filter.isNarrowed

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (sectioned) {
                            val brief = BriefBuilder.build(
                                stories = ready.stories,
                                watchlist = ready.watchlist,
                                sinceMillis = MarketClock.lastCloseMillis(ready.nowMillis),
                                expanded = expanded.toSet(),
                            )
                            item { BriefHeader(brief, ready.nowMillis) }
                            for (section in brief.sections) {
                                item(key = "header-${section.key}") { SectionHeader(section) }
                                // Keyed on cluster id so reordering after a refresh
                                // reuses composables rather than rebuilding the window.
                                items(section.stories, key = { it.article.clusterId }) { card(it) }
                                if (section.hiddenCount > 0) {
                                    item(key = "more-${section.key}") {
                                        TextButton(
                                            onClick = { expanded = expanded + section.key },
                                            modifier = Modifier.padding(start = 8.dp),
                                        ) { Text("Show ${section.hiddenCount} more") }
                                    }
                                }
                            }
                        } else {
                            items(ready.stories, key = { it.article.clusterId }) { card(it) }
                        }
                    }
                }
            }
        }
    }

    detail?.let { selected ->
        StorySheet(
            article = selected.article,
            nowMillis = ready?.nowMillis ?: System.currentTimeMillis(),
            tiers = ready?.tiers.orEmpty(),
            onOpen = { onOpenStory(selected); detail = null },
            onShare = { onShareStory(selected) },
            onToggleSave = { onToggleSave(selected) },
            onSelectSymbol = { symbol -> onSelectSymbol(symbol); detail = null },
            onSetTier = onSetTier,
            onSelectSector = { onSelectSector(it); detail = null },
            onMute = { onMute(it); detail = null },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun FilterRow(
    counts: Map<CategoryGroup, Int>,
    filter: FeedFilter,
    watchlistEmpty: Boolean,
    onSelectGroup: (CategoryGroup?) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleUnread: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("All", selected = filter.group == null && !filter.watchlistOnly) {
            onSelectGroup(null)
        }
        // Labelled for what it actually does: with no watchlist yet the filter falls
        // back to "any recognised company", and calling that "Watchlist" would be a lie.
        Chip(
            label = if (watchlistEmpty) "Tagged" else "Watchlist",
            selected = filter.watchlistOnly,
            onClick = onToggleWatchlist,
        )
        Chip("Saved", selected = filter.savedOnly, onClick = onToggleSaved)
        Chip("Unread", selected = filter.unreadOnly, onClick = onToggleUnread)
        for (group in CategoryGroup.entries) {
            val count = counts[group] ?: 0
            if (count == 0) continue
            Chip(
                label = "${group.label} $count",
                selected = filter.group == group,
            ) {
                onSelectGroup(if (filter.group == group) null else group)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun BriefHeader(brief: Brief, nowMillis: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            text = "${brief.totalStories} stories " +
                RelativeTime.closeLabel(brief.sinceMillis, nowMillis),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (brief.watchlistCount > 0) {
            Text(
                text = "${brief.watchlistCount} on your watchlist",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(section: BriefSection) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = section.totalCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
        )
    }
}

@Composable
private fun ActiveFilterBanner(
    label: String,
    action: String?,
    onAction: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

/**
 * Distinguishes "nothing matched" from "nothing fetched yet".
 *
 * Without that split, a filter that happens to match nothing looks exactly like a broken
 * sync, and the obvious next action — Refresh — is the wrong one.
 */
@Composable
private fun EmptyState(refreshing: Boolean, narrowed: Boolean, onClearFilters: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    refreshing -> "Fetching feeds…"
                    narrowed -> "No stories match that."
                    else -> "Nothing here yet.\nPull the first batch with Refresh."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (narrowed && !refreshing) {
                TextButton(onClick = onClearFilters) { Text("Clear filters") }
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (message == "Loading…") {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}
