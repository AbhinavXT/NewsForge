package com.abhinavxt.newsforge.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.rank.PollingPolicy
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.model.ScoredArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
        val tiers: Map<String, WatchTier>,
        val refreshing: Boolean,
        val lastError: String?,
        val nowMillis: Long,
    ) : FeedUiState

    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(
    private val repository: NewsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val filter = MutableStateFlow(FeedFilter())
    private val mode = MutableStateFlow(FeedMode.defaultFor(clock()))
    private val refreshing = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)

    private val tiers: StateFlow<Map<String, WatchTier>> = repository.watchlistTiers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /**
     * Recomputed whenever the store, the filter or the mode changes.
     *
     * `nowMillis` is captured per emission and passed down to the UI rather than read
     * inside composables, so the whole list agrees on one clock reading — otherwise two
     * cards rendered a frame apart can disagree about what "12m" means.
     */
    private val visible: StateFlow<List<ScoredArticle>> =
        combine(repository.stories(), repository.overnightBrief(), mode) { live, brief, current ->
            if (current == FeedMode.BRIEF) brief else live
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
        val tiers: Map<String, WatchTier>,
        val mutes: List<MuteRule>,
        val refreshing: Boolean,
        val error: String?,
    )

    private val chrome = combine(
        tiers,
        repository.muteRules(),
        refreshing,
        lastError,
    ) { watched, mutes, isRefreshing, error ->
        Chrome(watched, mutes, isRefreshing, error)
    }

    val uiState: StateFlow<FeedUiState> =
        combine(visible, filter, mode, chrome) { all, currentFilter, currentMode, current ->
            FeedUiState.Ready(
                stories = FeedFiltering.apply(all, currentFilter, current.tiers.keys, current.mutes),
                // Counted after muting: a chip promising eleven stories that opens on
                // three would be worse than no count.
                chipCounts = FeedFiltering.chipCounts(
                    FeedFiltering.apply(all, FeedFilter(), current.tiers.keys, current.mutes)
                ),
                filter = currentFilter,
                mode = currentMode,
                watchlist = current.tiers.keys,
                tiers = current.tiers,
                refreshing = current.refreshing,
                lastError = current.error,
                nowMillis = clock(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FeedUiState.Loading)

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
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val report = repository.refresh()
                lastError.value = when {
                    report.allFailed -> "No feeds reachable — check the connection"
                    report.failedFeeds.isNotEmpty() ->
                        "${report.failedFeeds.size} feeds failed"
                    else -> null
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
            if (!refreshing.value) refresh()
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

        /** How often to re-check the clock when polling is paused. */
        private const val IDLE_RECHECK_MS = 15 * 60_000L

        fun factory(repository: NewsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedViewModel(repository) }
        }
    }
}
