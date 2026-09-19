@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package com.abhinavxt.newsforge.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.core.tag.SymbolSearch
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.tag.SymbolDirectory
import com.abhinavxt.newsforge.data.NewsRepository.Companion.MIN_SEARCH_CHARS
import com.abhinavxt.newsforge.data.PriceHistoryRepository
import com.abhinavxt.newsforge.data.QuoteRepository
import com.abhinavxt.newsforge.data.VolumeHistoryRepository
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.rank.PollingPolicy
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.ingest.FeedRanking
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.data.model.WatchlistView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface FeedUiState {

    data object Loading : FeedUiState

    /**
     * @param stories already filtered and ranked.
     * @param chipCounts computed before filtering, so a chip never reads zero merely
     *   because another chip is active.
     */
    data class Ready(
        val stories: List<ScoredArticle>,
        val chipCounts: Map<CategoryGroup, Int>,
        val filter: FeedFilter,
        val mode: FeedMode,
        val watchlist: Set<String>,
        /** Effective tier per symbol — the stronger of what you set and what you hold. */
        val tiers: Map<String, WatchTier>,
        /** What you set by hand. What the tier chips in the sheet actually control. */
        val manualTiers: Map<String, WatchTier> = emptyMap(),
        /** What your open positions say. Shown, never edited from the phone. */
        val deskTiers: Map<String, WatchTier> = emptyMap(),
        /** Named screens from the desk, as filter chips. Empty when none have arrived. */
        val screens: Map<String, List<String>> = emptyMap(),
        /** Current prices and what they were earlier, for measuring reactions. */
        val prices: PriceBook = PriceBook(),
        val refreshing: Boolean,
        val lastError: String?,
        val nowMillis: Long,
    ) : FeedUiState

    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(
    private val repository: NewsRepository,
    /**
     * Read-only, and only for quotes.
     *
     * The two repositories are kept apart on purpose — desk messages never enter the
     * article store, never reach the ranker, never appear in the brief — and that holds
     * here. This screen joins them at the point of display and nowhere else.
     */
    private val deskRepository: DeskRepository,
    private val quoteRepository: QuoteRepository,
    private val priceHistoryRepository: PriceHistoryRepository,
    private val volumeHistoryRepository: VolumeHistoryRepository,
    private val directory: SymbolDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val filter = MutableStateFlow(FeedFilter())

    /**
     * Companies matching what is in the search box, whether or not they have coverage.
     *
     * The search box used to reach only stories, which meant a quiet company was
     * unreachable: its research screen exists and its chart needs no news at all, but
     * nothing could navigate to it. These rows are that route.
     *
     * Debounced, and on the query alone — the other filters do not change which company a
     * word names, and rebuilding this when somebody toggles Unread would be work for a
     * result that cannot differ.
     */
    val symbolMatches: StateFlow<List<SymbolEntry>> = filter
        .map { it.query.trim() }
        .distinctUntilChanged()
        .debounce(TYPING_PAUSE_MS)
        .mapLatest { query ->
            if (query.length < SymbolSearch.MIN_QUERY) emptyList() else directory.search(query)
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mode = MutableStateFlow(FeedMode.defaultFor(clock()))
    private val refreshing = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)

    private val watchlistView: StateFlow<WatchlistView> = repository.watchlistView()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WatchlistView())

    /**
     * Every stored story, ranked. One subscription, not two.
     *
     * The brief used to be a second flow over the same query, which meant every insert
     * during a sync re-ran and re-ranked the whole table twice. It is a subset of this
     * list bounded by the previous close, and since a score depends only on the article
     * and never on the set it sits in, taking that subset after ranking gives exactly
     * what the second query did.
     */
    private val stories: StateFlow<List<ScoredArticle>> =
        filter
            .map { it.query.trim() }
            .distinctUntilChanged()
            // Typing is not a query. Without this every keystroke starts and cancels a
            // database read, and the list flickers through the prefixes of the word.
            .debounce { text -> if (text.length >= MIN_SEARCH_CHARS) TYPING_PAUSE_MS else 0L }
            .flatMapLatest { text ->
                // A search is a different question from the feed, not a narrowing of it:
                // it reaches ninety days rather than the feed's week, and it matches
                // tickers and outlets that the loaded list may not even contain.
                if (text.length >= MIN_SEARCH_CHARS) repository.search(text)
                else repository.stories()
            }
            .catch { e ->
                if (e is CancellationException) throw e
                lastError.value = e.message ?: "Could not read stored news"
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Chrome state folded into one flow.
     *
     * `combine` tops out at five typed flows, and dropping one of these to fit would mean
     * reading it as `.value` instead — which quietly stops it triggering an emission, so
     * an error banner would only appear the next time something *else* changed.
     */
    private data class Chrome(
        val watchlist: WatchlistView,
        val screens: Map<String, List<String>>,
        val mutes: List<MuteRule>,
        val prices: PriceBook,
        val refreshing: Boolean,
        val error: String?,
    )

    /**
     * Desk over exchange, symbol by symbol.
     *
     * The desk's numbers are real-time and licensed; the exchange's are published late by
     * regulation. So the bridge wins wherever it has an answer, and the exchange fills in
     * everything else — which, with TickerForge off, is everything. Merged here rather
     * than in either repository because neither should have to know the other exists.
     */
    private val quotes = combine(
        deskRepository.latestQuotes(),
        quoteRepository.quotes(),
        priceHistoryRepository.recent(),
        volumeHistoryRepository.relativeVolumes(),
    ) { desk, exchange, samples, volumes -> PriceBook(exchange + desk, samples, volumes) }

    private val chrome = combine(
        combine(watchlistView, repository.screens()) { watched, screens -> watched to screens },
        repository.muteRules(),
        quotes,
        refreshing,
        lastError,
    ) { (watched, screens), mutes, prices, isRefreshing, error ->
        Chrome(watched, screens, mutes, prices, isRefreshing, error)
    }

    /**
     * Recomputed whenever the store, the filter or the mode changes.
     *
     * `nowMillis` is captured once per emission and passed down to the UI rather than
     * read inside composables, so the whole list agrees on one clock reading — otherwise
     * two cards rendered a frame apart can disagree about what "12m" means.
     *
     * Off the main thread, because this is where the real work is: ranking is upstream,
     * but muting, filtering and counting a week of stories still costs more than a frame
     * and would otherwise run wherever `collectAsStateWithLifecycle` collects, which is
     * the main thread.
     */
    val uiState: StateFlow<FeedUiState> =
        combine(stories, filter, mode, chrome) { all, currentFilter, currentMode, current ->
            val now = clock()
            val scoped =
                if (currentMode == FeedMode.BRIEF) FeedRanking.overnightOf(all, now) else all
            // Muted once and reused: the visible list and the chip counts are two views
            // of the same population, and a chip promising eleven stories that opens on
            // three would be worse than no count at all.
            // The database has already matched the text; re-applying it in memory would
            // discard the rows it found by ticker or outlet, which are the ones the feed
            // could not have surfaced on its own.
            val narrowing = currentFilter.copy(query = "")
            val unmuted = FeedFiltering.applyMutes(scoped, current.mutes)
            FeedUiState.Ready(
                stories = FeedFiltering.applyFilter(
                    unmuted,
                    narrowing,
                    current.watchlist.symbols,
                    currentFilter.screen
                        ?.let { current.screens[it] }
                        ?.toSet()
                        .orEmpty(),
                ),
                chipCounts = FeedFiltering.chipCounts(unmuted),
                filter = currentFilter,
                mode = currentMode,
                watchlist = current.watchlist.symbols,
                tiers = current.watchlist.effective,
                manualTiers = current.watchlist.manual,
                deskTiers = current.watchlist.desk,
                prices = current.prices,
                screens = current.screens,
                refreshing = current.refreshing,
                lastError = current.error,
                nowMillis = now,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FeedUiState.Loading)

    val modeState: StateFlow<FeedMode> = mode.asStateFlow()

    fun selectGroup(group: CategoryGroup?) = filter.update { it.copy(group = group) }

    fun toggleWatchlistOnly() = filter.update { it.copy(watchlistOnly = !it.watchlistOnly) }

    fun toggleUnreadOnly() = filter.update { it.copy(unreadOnly = !it.unreadOnly) }

    fun toggleSavedOnly() = filter.update { it.copy(savedOnly = !it.savedOnly) }

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    /**
     * Focuses the feed on one company.
     *
     * Clears [FeedFilter.group] at the same time: arriving at a symbol from inside the
     * "Movers" chip and then seeing only that company's movers is almost never what was
     * meant — you tapped the ticker to see everything about it.
     */
    fun setSymbol(symbol: String?) = filter.update { it.copy(symbol = symbol, group = null) }

    /**
     * Focuses a sector, clearing the symbol.
     *
     * Arriving at "Defence" from one company and still seeing only that company would
     * defeat the point — you tapped the sector to widen, not to narrow twice.
     */
    fun setSector(sector: Sector?) = filter.update { it.copy(sector = sector, symbol = null) }

    /**
     * Picks a desk screen, or clears it by tapping the one already on.
     *
     * Replaces rather than intersects: two screens at once is a question nobody asked,
     * and an empty feed with two chips lit is a puzzle rather than an answer.
     */
    fun toggleScreen(name: String) =
        filter.update { it.copy(screen = if (it.screen == name) null else name) }

    fun clearFilters() = filter.update { FeedFilter() }

    fun mute(rule: MuteRule) {
        viewModelScope.launch {
            try {
                repository.addMute(rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not mute that"
            }
        }
    }

    fun setMode(next: FeedMode) {
        mode.value = next
    }

    fun clearError() {
        lastError.value = null
    }

    /**
     * Manual refresh runs in-process rather than enqueuing work, so the spinner reflects
     * something real. Background polling stays with WorkManager.
     */
    fun refresh(minGapMillis: Long = 0L) {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val report = repository.refresh(minGapMillis)
                lastError.value = when {
                    report.allFailed -> "No feeds reachable — check the connection"
                    report.failedFeeds.isNotEmpty() ->
                        "${report.failedFeeds.size} feeds failed"
                    else -> null
                }
                // After the news, and never allowed to fail it. A missing price is an
                // ordinary state — the endpoint is session-gated and blocks a phone often
                // enough — and an error banner over the feed because a nice-to-have did
                // not load would be the wrong trade.
                try {
                    quoteRepository.refresh(repository.pricedSymbols())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "quote refresh failed", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Refresh failed"
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Sets a symbol's exposure tier, adding it to the watchlist if new. */
    fun setTier(symbol: String, tier: WatchTier) {
        viewModelScope.launch {
            try {
                repository.setWatchTier(symbol, tier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not update the watchlist"
            }
        }
    }

    fun toggleWatchlist(symbol: String) {
        viewModelScope.launch {
            try {
                repository.toggleWatchlist(symbol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not update the watchlist"
            }
        }
    }

    /**
     * Polls while the screen is visible, at a cadence set by the session phase.
     *
     * Called from a lifecycle-scoped effect rather than launched here, so it stops when
     * the app is backgrounded. The interval is re-read every iteration so the cadence
     * follows the clock across the open and the close without restarting the loop.
     */
    suspend fun autoRefreshWhileVisible() {
        while (currentCoroutineContext().isActive) {
            val interval = PollingPolicy.intervalMillisAt(clock())
            if (interval == null) {
                // Weekend: nothing to poll for. Wait rather than exit, so the loop picks
                // straight back up if the app is still open on Monday morning.
                delay(IDLE_RECHECK_MS)
                continue
            }
            delay(interval)
            // The gap is the tick itself: if the live watch or the periodic worker
            // polled within the last interval, this tick has nothing left to fetch, and
            // pull-to-refresh still goes through unconditionally.
            if (!refreshing.value) refresh(minGapMillis = interval)
        }
    }

    fun onStoryOpened(clusterId: String) {
        viewModelScope.launch {
            try {
                repository.markRead(clusterId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Read state is a convenience; failing to record it must not surface.
            }
        }
    }

    /** Swipe target. [onStoryOpened] only ever marks read; this has to go both ways. */
    fun setRead(clusterId: String, read: Boolean) {
        viewModelScope.launch {
            try {
                repository.markRead(clusterId, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not update the story"
            }
        }
    }

    fun toggleSaved(articleId: String, saved: Boolean) {
        viewModelScope.launch {
            try {
                repository.setSaved(articleId, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not update saved state"
            }
        }
    }

    companion object {
        /** Survives a rotation without tearing down the database flows. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Long enough to finish a word, short enough not to feel like waiting. */
        private const val TYPING_PAUSE_MS = 250L

        /** How often to re-check the clock when polling is paused. */
        private const val IDLE_RECHECK_MS = 15 * 60_000L

        private const val TAG = "FeedViewModel"

        fun factory(
            repository: NewsRepository,
            deskRepository: DeskRepository,
            quoteRepository: QuoteRepository,
            priceHistoryRepository: PriceHistoryRepository,
            volumeHistoryRepository: VolumeHistoryRepository,
            directory: SymbolDirectory,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FeedViewModel(
                    repository,
                    deskRepository,
                    quoteRepository,
                    priceHistoryRepository,
                    volumeHistoryRepository,
                    directory,
                )
            }
        }
    }
}
