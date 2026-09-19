@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun FeedScreen(
    state: FeedUiState,
    onOpenStory: (ScoredArticle) -> Unit,
    onShareStory: (ScoredArticle) -> Unit,
    onMarkRead: (ScoredArticle) -> Unit,
    onToggleRead: (ScoredArticle) -> Unit,
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
    onToggleScreen: (String) -> Unit,
    onSelectGroup: (CategoryGroup?) -> Unit,
    onToggleWatchlist: () -> Unit,
    onSetMode: (FeedMode) -> Unit,
    onRefresh: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    /** Companies matching the search box, listed above the stories. */
    symbolMatches: List<SymbolEntry> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val ready = state as? FeedUiState.Ready
    // Saveable, like the query it reveals. The query lives in the view model and survives
    // a rotation on its own, so a non-saveable flag here meant the field vanished while
    // its filter stayed applied — a narrowed feed with nothing on screen explaining why.
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Held as an id, not as a row. A snapshot goes stale the moment the sheet acts on it:
    // starring a story wrote to the database, the list updated underneath, and the sheet
    // went on rendering the copy it was opened with — so the star did not move.
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    // The row as it was when opened, kept only for the case where acting on the story
    // drops it out of the filtered list — under the Unread chip, reading one removes it.
    // Closing the sheet from under the reader would be worse than one stale field.
    var detailFallback by remember { mutableStateOf<ScoredArticle?>(null) }
    val detail = detailId?.let { id ->
        ready?.stories?.firstOrNull { it.article.clusterId == id } ?: detailFallback
    }

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
                    // Mode stays a word. It names which of two feeds you are looking at,
                    // and no glyph says "everything since the close" unambiguously.
                    TextButton(onClick = {
                        onSetMode(
                            if (ready?.mode == FeedMode.BRIEF) FeedMode.LIVE else FeedMode.BRIEF
                        )
                    }) {
                        Text(if (ready?.mode == FeedMode.BRIEF) "Live" else "Brief")
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) onQueryChange("")
                    }) {
                        Icon(
                            painter = painterResource(
                                if (searching) R.drawable.ic_close else R.drawable.ic_search
                            ),
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    // No Refresh button: the pull gesture below covers it, and its
                    // spinner reports progress that a button caption cannot.
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
                    SearchField(
                        query = ready.filter.query,
                        onQueryChange = onQueryChange,
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
                // Above the category chips, not among them. A screen narrows to a set of
                // companies while those narrow by kind of event, and mixing two axes in
                // one strip makes the pair look mutually exclusive when they compose.
                ScreenRow(
                    screens = ready.screens,
                    selected = ready.filter.screen,
                    onToggle = onToggleScreen,
                )
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

                ready != null -> {
                    // Grouped unless the reader has narrowed the list themselves.
                    // Sectioning a filtered list fights the filter: they have already
                    // said what they want, and grouping the remainder by kind just puts
                    // headings between three items.
                    val sectioned = !ready.filter.isNarrowed

                    val row: @Composable (ScoredArticle, Boolean) -> Unit = { scored, lead ->
                        // A filing is a ticker, a subject and a time. Given a card it
                        // takes a card's worth of screen, and fourteen of them arriving
                        // together is the wall this grouping exists to stop.
                        if (scored.article.isFiling && !lead) {
                            FilingRow(
                                article = scored.article,
                                nowMillis = ready.nowMillis,
                                watchlist = ready.watchlist,
                                onOpen = {
                                    detailId = scored.article.clusterId
                                    detailFallback = scored
                                    onMarkRead(scored)
                                },
                            )
                        } else {
                            SwipeableStoryCard(
                                article = scored.article,
                                nowMillis = ready.nowMillis,
                                watchlist = ready.watchlist,
                                // Tap opens the sheet, not the browser: most headlines
                                // need only two lines to triage, and a browser round trip
                                // for each of forty is the slow path.
                                onOpen = {
                                    detailId = scored.article.clusterId
                                    detailFallback = scored
                                    onMarkRead(scored)
                                },
                                onToggleSave = { onToggleSave(scored) },
                                onToggleRead = { onToggleRead(scored) },
                                onSelectSymbol = onSelectSymbol,
                                onToggleSymbol = onToggleSymbol,
                                lead = lead,
                                // Nothing above a flat list says what kind of story this
                                // is, so the card says it. Under a heading it would be
                                // the same word nine times.
                                showCategory = !sectioned,
                                prices = ready.prices,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val card: @Composable (ScoredArticle) -> Unit = { scored -> row(scored, false) }

                    // The empty state lives inside the list rather than replacing it, so
                    // the pull gesture still works on the screen where it is most wanted:
                    // the one with nothing on it.
                    PullToRefreshBox(
                        isRefreshing = ready.refreshing,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            if (symbolMatches.isNotEmpty()) {
                                item(key = "companies-header") {
                                    Text(
                                        text = "COMPANIES",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Chalk500,
                                        modifier = Modifier.padding(
                                            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp,
                                        ),
                                    )
                                }
                                items(symbolMatches, key = { "company-${it.symbol}" }) { entry ->
                                    CompanyRow(entry) { onOpenSymbol(entry.symbol) }
                                }
                                item(key = "companies-divider") {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                            // Not `else`: a query can match both a company and stories that
                            // mention it, and the two answer different questions — "show me
                            // this company" and "what has been written about it".
                            if (ready.stories.isEmpty() && symbolMatches.isEmpty()) {
                                item {
                                    EmptyState(
                                        refreshing = ready.refreshing,
                                        narrowed = ready.filter.isNarrowed,
                                        onClearFilters = onClearFilters,
                                        modifier = Modifier.fillParentMaxSize(),
                                    )
                                }
                            } else if (sectioned && ready.mode == FeedMode.BRIEF) {
                                val brief = BriefBuilder.build(
                                    stories = ready.stories,
                                    watchlist = ready.watchlist,
                                    sinceMillis = MarketClock.lastCloseMillis(ready.nowMillis),
                                    expanded = expanded.toSet(),
                                )
                                item { BriefHeader(brief, ready.nowMillis) }
                                sections(brief.sections, card, expanded) { expanded = it }
                            } else if (sectioned) {
                                val layout = FeedLayout.build(
                                    stories = ready.stories,
                                    watchlist = ready.watchlist,
                                    expanded = expanded.toSet(),
                                )
                                // The lead sits above the grouping, ranked on score
                                // alone: capping sections must not bury the two stories
                                // that actually matter under a heading.
                                items(
                                    layout.lead,
                                    key = { it.article.clusterId },
                                ) { row(it, !layout.isFlat) }
                                sections(layout.sections, card, expanded) { expanded = it }
                            } else {
                                items(ready.stories, key = { it.article.clusterId }) { card(it) }
                            }
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
            manualTiers = ready?.manualTiers.orEmpty(),
            deskTiers = ready?.deskTiers.orEmpty(),
            prices = ready?.prices ?: PriceBook(),
            onOpen = { onOpenStory(selected); detailId = null },
            onShare = { onShareStory(selected) },
            onToggleSave = { onToggleSave(selected) },
            onSelectSymbol = { symbol -> onSelectSymbol(symbol); detailId = null },
            onSetTier = onSetTier,
            onSelectSector = { onSelectSector(it); detailId = null },
            onMute = { onMute(it); detailId = null },
            onDismiss = { detailId = null },
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

/**
 * A run of sections, flattened into the enclosing list.
 *
 * An extension on [LazyListScope] rather than a composable, because a section has to
 * contribute its items to the one scroller — wrapping each in its own would break item
 * reuse and give every heading a nested scroll region.
 */
private fun LazyListScope.sections(
    sections: List<BriefSection>,
    card: @Composable (ScoredArticle) -> Unit,
    expanded: List<String>,
    onExpand: (List<String>) -> Unit,
) {
    for (section in sections) {
        item(key = "header-${section.key}") { SectionHeader(section) }
        // Keyed on cluster id so reordering after a refresh reuses composables rather
        // than rebuilding the window.
        items(section.stories, key = { it.article.clusterId }) { card(it) }
        if (section.hiddenCount > 0) {
            item(key = "more-${section.key}") {
                TextButton(
                    onClick = { onExpand(expanded + section.key) },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Show ${section.hiddenCount} more") }
            }
        }
    }
}

/**
 * The colour standing for a whole heading.
 *
 * Section keys are either the watchlist or a [CategoryGroup] name — the enum is matched by
 * name rather than valueOf so a key that ever stops being one does not crash the feed.
 */
@Composable
private fun sectionAccent(key: String): Color = when (key) {
    BriefBuilder.KEY_WATCHLIST -> MaterialTheme.colorScheme.primary
    else -> CategoryGroup.entries.firstOrNull { it.name == key }?.accent ?: Chalk500
}

@Composable
private fun BriefHeader(brief: Brief, nowMillis: Long) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp)) {
        Text(
            text = "${brief.totalStories} stories " +
                RelativeTime.closeLabel(brief.sinceMillis, nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BriefTape(brief.sections)
    }
}

/**
 * The shape of the night, before any of its contents.
 *
 * Answers the question the brief is actually opened with — was it a quiet night or a
 * loud one, and loud about what — in one glance, without scrolling. The counts are the
 * section totals, so the tape and the sections below can never disagree.
 */
@Composable
private fun BriefTape(sections: List<BriefSection>) {
    if (sections.size < 2) return
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (section in sections) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(sectionAccent(section.key))
                )
                Text(
                    text = section.totalCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = section.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Chalk500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A quiet heading: a dot in the section's colour, its name, its true size.
 *
 * The count is the total rather than what is shown, so "Results 9" over three stories
 * reads as a deliberate cap rather than as nine stories having gone missing.
 */
@Composable
private fun SectionHeader(section: BriefSection) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(sectionAccent(section.key))
        )
        Text(
            text = section.title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = section.totalCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
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
private fun EmptyState(
    refreshing: Boolean,
    narrowed: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    refreshing -> "Fetching feeds…"
                    narrowed -> "No stories match that."
                    else -> "Nothing here yet.\nPull down to fetch the first batch."
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

/**
 * Screens the desk has sent, as chips.
 *
 * The bridge already carried what you hold; this carries what your own screener picked
 * out and has no opinion about yet — the twelve names that passed a momentum filter this
 * morning. Those are exactly the names whose news you want, and before this the only way
 * to act on a screen was to remember it and type tickers by hand.
 *
 * Absent entirely when no screen has arrived, rather than showing an empty strip that
 * invites the question of what it is for.
 */
@Composable
private fun ScreenRow(
    screens: Map<String, List<String>>,
    selected: String?,
    onToggle: (String) -> Unit,
) {
    if (screens.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((name, symbols) in screens) {
            FilterChip(
                selected = name == selected,
                onClick = { onToggle(name) },
                // The count is the screen's size, not how many stories it matched: an
                // empty run is a real answer, and a chip reading zero says the screener
                // ran and found nothing rather than that the feed is broken.
                label = { Text("$name ${symbols.size}") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * One searchable company, as a tappable row.
 *
 * Ticker first and large, name underneath. The ticker is what was typed and what the
 * research screen is titled with; the name is how you confirm it is the right company
 * before committing a tap.
 */
@Composable
private fun CompanyRow(entry: SymbolEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.symbol,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "→",
            style = MaterialTheme.typography.titleSmall,
            color = Chalk500,
        )
    }
}

/**
 * The search box, holding its own text.
 *
 * A text field whose `value` comes back from a view model is only correct when the round
 * trip is synchronous, and this one is not: a keystroke goes through `setQuery`, a
 * four-way `combine`, `flowOn(Dispatchers.Default)` and `stateIn` before it can be
 * rendered. Compose draws whatever `value` says at that moment, so anything slow upstream
 * shows up as dropped characters — and something slow enough shows up as a field that
 * will not accept typing at all.
 *
 * So the field owns the text and reports it outward. The view model stays the source of
 * truth for the *filter*; this is only the source of truth for what is currently typed,
 * which is a different thing and has always belonged here.
 *
 * External changes are still adopted — the Clear-filters button and closing the search
 * icon both empty the query from elsewhere and the box has to follow. But only changes
 * that did not originate here: the view model echoes each keystroke back, and adopting
 * those would be the original bug again, one step removed. Type "abc" quickly and the
 * echo of "a" would arrive while the box holds "abc" and truncate it. So the last value
 * sent out is remembered, and anything matching it is this field hearing itself.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(query) }
    var lastSent by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != lastSent) {
            text = query
            lastSent = query
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            lastSent = it
            onQueryChange(it)
        },
        label = { Text("Search headlines and tickers") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
