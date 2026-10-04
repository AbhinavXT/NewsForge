@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import com.abhinavxt.newsforge.ui.components.ChipStrip
import com.abhinavxt.newsforge.ui.components.Dot
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.GroupCard
import com.abhinavxt.newsforge.ui.components.GroupDivider
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.MarketStatusPill
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SectionTitle
import com.abhinavxt.newsforge.ui.components.SegmentedControl
import com.abhinavxt.newsforge.ui.components.StorySkeleton
import com.abhinavxt.newsforge.ui.theme.Hairline
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.abhinavxt.newsforge.ui.components.PrimaryButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onOpenSectors: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onSetMode: (FeedMode) -> Unit,
    onRefresh: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    /** Starts catch-up over these stories, in this order. */
    onCatchUp: (List<String>) -> Unit = {},
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
    val listState = rememberLazyListState()
    val arrivals = rememberArrivals(ready, listState)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Unread in what is on screen, in the order it is ranked: what catch-up walks through.
    val unread = ready?.stories.orEmpty().filterNot { it.article.read }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                title = when (ready?.mode) {
                    FeedMode.BRIEF -> "Overnight brief"
                    else -> "Live feed"
                },
                subtitle = ready?.let { "${it.stories.size} stories" },
                eyebrow = ready?.let { { MarketStatusPill(it.nowMillis) } },
                actions = {
                    // Only with enough to make a session of it; one unread story is a tap
                    // away already.
                    if (unread.size >= CATCH_UP_MIN) {
                        IconCircleButton(
                            icon = R.drawable.ic_done_all,
                            contentDescription = "Catch up on ${unread.size} unread",
                            onClick = { onCatchUp(unread.take(CATCH_UP_MAX).map { it.article.clusterId }) },
                        )
                    }
                    // Ahead of search, because it answers a question the reader has
                    // before they know what to search for.
                    IconCircleButton(
                        icon = R.drawable.ic_chart,
                        contentDescription = "Sectors",
                        onClick = onOpenSectors,
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
                    // No Refresh button: the pull gesture below covers it, and its
                    // spinner reports progress that a button caption cannot.
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ready != null) {
                // Mode as a segmented control: the two are views of one feed, not filters
                // that combine, and two segments say that where a single toggling word
                // left the reader guessing which one they were on.
                SegmentedControl(
                    options = listOf("Live", "Overnight brief"),
                    selectedIndex = if (ready.mode == FeedMode.BRIEF) 1 else 0,
                    onSelect = { onSetMode(if (it == 1) FeedMode.BRIEF else FeedMode.LIVE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenGutter, vertical = 4.dp),
                )
                AnimatedVisibility(
                    visible = searching,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SearchField(
                        query = ready.filter.query,
                        onQueryChange = onQueryChange,
                    )
                }
                ready.filter.sector?.let { sector ->
                    ActiveFilterBanner(
                        kind = "Sector",
                        label = sector.label,
                        action = null,
                        onAction = {},
                        onClear = { onSelectSector(null) },
                    )
                }
                ready.filter.symbol?.let { symbol ->
                    ActiveFilterBanner(
                        kind = "Company",
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
            }

            when {
                state is FeedUiState.Loading -> Column(Modifier.padding(top = 8.dp)) {
                    repeat(5) { StorySkeleton() }
                }

                state is FeedUiState.Error -> EmptyState(
                    icon = R.drawable.ic_refresh,
                    title = "Couldn't load the feed",
                    body = state.message,
                )

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
                        val inset = Modifier.padding(
                            horizontal = ScreenGutter,
                            vertical = if (scored.article.isFiling && !lead) 3.dp else 5.dp,
                        )
                        if (scored.article.isFiling && !lead) {
                            SwipeableFilingRow(
                                modifier = inset,
                                article = scored.article,
                                nowMillis = ready.nowMillis,
                                watchlist = ready.watchlist,
                                onOpen = {
                                    detailId = scored.article.clusterId
                                    detailFallback = scored
                                    onMarkRead(scored)
                                },
                                onToggleSave = { onToggleSave(scored) },
                                onToggleRead = { onToggleRead(scored) },
                            )
                        } else {
                            SwipeableStoryCard(
                                modifier = inset,
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
                    }
                    val card: @Composable (ScoredArticle) -> Unit = { scored -> row(scored, false) }

                    // The empty state lives inside the list rather than replacing it, so
                    // the pull gesture still works on the screen where it is most wanted:
                    // the one with nothing on it.
                    PullToRefreshBox(
                        isRefreshing = ready.refreshing,
                        onRefresh = {
                            // The pull crossing its threshold is a commitment; a tick says
                            // it registered before the spinner has had a frame to appear.
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            onRefresh()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        ) {
                            if (symbolMatches.isNotEmpty()) {
                                item(key = "companies-header") {
                                    SectionTitle("Companies", count = symbolMatches.size)
                                }
                                item(key = "companies") {
                                    GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                                        symbolMatches.forEachIndexed { index, entry ->
                                            if (index > 0) GroupDivider(inset = 62.dp)
                                            CompanyRow(entry) { onOpenSymbol(entry.symbol) }
                                        }
                                    }
                                }
                                if (ready.stories.isNotEmpty()) {
                                    item(key = "stories-header") {
                                        SectionTitle("Stories", count = ready.stories.size)
                                    }
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
                                item {
                                    BriefHeader(
                                        brief = brief,
                                        nowMillis = ready.nowMillis,
                                        unread = unread.size,
                                        onCatchUp = {
                                            onCatchUp(unread.take(CATCH_UP_MAX).map { it.article.clusterId })
                                        },
                                    )
                                }
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
                        NewStoriesPill(
                            count = arrivals.count,
                            onClick = {
                                arrivals.clear()
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    detail?.let { selected ->
        StorySheet(
            article = selected.article,
            moveProbability = selected.moveProbability,
            drivers = selected.drivers,
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
    ChipStrip {
        NfChip("All", selected = filter.group == null && !filter.watchlistOnly, onClick = {
            onSelectGroup(null)
        })
        // Labelled for what it actually does: with no watchlist yet the filter falls
        // back to "any recognised company", and calling that "Watchlist" would be a lie.
        NfChip(
            label = if (watchlistEmpty) "Tagged" else "Watchlist",
            selected = filter.watchlistOnly,
            onClick = onToggleWatchlist,
        )
        NfChip("Saved", selected = filter.savedOnly, onClick = onToggleSaved)
        NfChip("Unread", selected = filter.unreadOnly, onClick = onToggleUnread)
        for (group in CategoryGroup.entries) {
            val count = counts[group] ?: 0
            if (count == 0) continue
            NfChip(
                label = group.label,
                count = count,
                accent = group.accent,
                selected = filter.group == group,
                onClick = { onSelectGroup(if (filter.group == group) null else group) },
            )
        }
    }
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
                Text(
                    text = "Show ${section.hiddenCount} more ${section.title.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenGutter, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { onExpand(expanded + section.key) }
                        .padding(vertical = 10.dp),
                )
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

/**
 * Stories that arrived while the reader was scrolled down the list.
 *
 * Counted only while the list is not at the top: at the top, a new story simply appears
 * where the eye already is, and a pill announcing it would be noise. Reset when the
 * filter or mode changes, because a different list is not a list with new things in it.
 */
private class Arrivals {
    var ids by mutableStateOf(emptySet<String>())
        private set
    var seen: Set<String>? = null
    var scope: Any? = null

    val count: Int get() = ids.size

    fun observe(scope: Any, current: List<String>, atTop: Boolean) {
        val known = seen
        if (known == null || scope != this.scope) {
            this.scope = scope
            seen = current.toSet()
            ids = emptySet()
            return
        }
        val fresh = current.filterTo(LinkedHashSet()) { it !in known }
        seen = known + fresh
        ids = if (atTop) emptySet() else (ids + fresh).intersect(current.toSet())
    }

    fun clear() {
        ids = emptySet()
    }
}

@Composable
private fun rememberArrivals(ready: FeedUiState.Ready?, listState: LazyListState): Arrivals {
    val arrivals = remember { Arrivals() }
    val haptics = LocalHapticFeedback.current
    val ids = ready?.stories?.map { it.article.clusterId }
    val scope = ready?.let { it.filter to it.mode }
    LaunchedEffect(ids, scope) {
        if (ids == null || scope == null) return@LaunchedEffect
        val before = arrivals.count
        arrivals.observe(scope, ids, atTop = listState.firstVisibleItemIndex == 0)
        if (arrivals.count > before) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    // Reaching the top by hand has read them, as far as the pill is concerned.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 }
            .collect { atTop -> if (atTop) arrivals.clear() }
    }
    return arrivals
}

@Composable
private fun NewStoriesPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .shadow(6.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "\u2191",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = "$count new ${if (count == 1) "story" else "stories"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun BriefHeader(brief: Brief, nowMillis: Long, unread: Int, onCatchUp: () -> Unit) {
    NfCard(Modifier.padding(horizontal = ScreenGutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = brief.totalStories.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = " stories " + RelativeTime.closeLabel(brief.sinceMillis, nowMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BriefTape(brief.sections)
        if (unread >= CATCH_UP_MIN) {
            PrimaryButton(
                text = "Catch up on $unread unread",
                icon = R.drawable.ic_done_all,
                onClick = onCatchUp,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}

/** Fewer than this and catch-up is a screen to step through three cards. */
private const val CATCH_UP_MIN = 3

/**
 * The highest-ranked this many, not every unread story. A Monday can leave four hundred,
 * and a pile that size is not something anyone works through card by card.
 */
private const val CATCH_UP_MAX = 30

/**
 * The shape of the night, before any of its contents.
 *
 * Answers the question the brief is actually opened with — was it a quiet night or a
 * loud one, and loud about what — in one glance, without scrolling. One bar split in
 * proportion to each section's size, so a night that was mostly results looks mostly
 * blue before a single number is read. The counts are the section totals, so the tape
 * and the sections below can never disagree.
 */
@Composable
private fun BriefTape(sections: List<BriefSection>) {
    if (sections.size < 2) return
    val total = sections.sumOf { it.totalCount }.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (section in sections) {
            Box(
                Modifier
                    .weight(section.totalCount.coerceAtLeast(1).toFloat() / total)
                    .fillMaxHeight()
                    .background(sectionAccent(section.key))
            )
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (section in sections) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dot(sectionAccent(section.key), 7.dp)
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = section.totalCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
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
    SectionTitle(
        title = section.title,
        count = section.totalCount,
        accent = sectionAccent(section.key),
    )
}

@Composable
private fun ActiveFilterBanner(
    kind: String,
    label: String,
    action: String?,
    onAction: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = kind.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
        IconCircleButton(
            icon = R.drawable.ic_close,
            contentDescription = "Clear $kind filter",
            onClick = onClear,
            container = Color.Transparent,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 36.dp,
        )
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
    when {
        refreshing -> EmptyState(
            icon = R.drawable.ic_refresh,
            title = "Fetching feeds…",
            modifier = modifier,
        )
        narrowed -> EmptyState(
            icon = R.drawable.ic_search,
            title = "No stories match that",
            body = "Try a wider filter, or clear them all.",
            actionLabel = "Clear filters",
            onAction = onClearFilters,
            modifier = modifier,
        )
        else -> EmptyState(
            icon = R.drawable.ic_news,
            title = "Nothing here yet",
            body = "Pull down to fetch the first batch.",
            modifier = modifier,
        )
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
    ChipStrip {
        Text(
            text = "SCREENS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Chalk500,
        )
        for ((name, symbols) in screens) {
            // The count is the screen's size, not how many stories it matched: an
            // empty run is a real answer, and a chip reading zero says the screener
            // ran and found nothing rather than that the feed is broken.
            NfChip(
                label = name,
                count = symbols.size,
                selected = name == selected,
                onClick = { onToggle(name) },
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.symbol.take(2),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.symbol,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = Chalk500,
            modifier = Modifier.size(18.dp),
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
    val focus = remember { FocusRequester() }
    // Opening search is a request to type; making the reader tap the field a second time
    // to get a keyboard is a step that exists only because nobody asked for focus.
    LaunchedEffect(Unit) { focus.requestFocus() }
    val update: (String) -> Unit = {
        text = it
        lastSent = it
        onQueryChange(it)
    }
    OutlinedTextField(
        value = text,
        onValueChange = update,
        placeholder = { Text("Search headlines, tickers, companies") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = Chalk500,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (text.isNotEmpty()) {
            {
                IconCircleButton(
                    icon = R.drawable.ic_close,
                    contentDescription = "Clear search",
                    onClick = { update("") },
                    container = Color.Transparent,
                    tint = Chalk500,
                    size = 36.dp,
                )
            }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Hairline,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .padding(horizontal = ScreenGutter, vertical = 6.dp),
    )
}
