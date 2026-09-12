package com.abhinavxt.newsforge.ui.symbol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.data.NewsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SymbolUiState(
    val symbol: String = "",
    val summary: TimelineSummary? = null,
    val sections: List<TimelineSection> = emptyList(),
    val events: List<UpcomingEvent> = emptyList(),
    val tier: WatchTier? = null,
    val quote: DeskPayload? = null,
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
)

class SymbolViewModel(
    private val repository: NewsRepository,
    private val deskRepository: DeskRepository,
    private val symbol: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val uiState: StateFlow<SymbolUiState> = combine(
        repository.storiesForSymbol(symbol),
        repository.eventsForSymbol(symbol),
        repository.watchlistTiers(),
        deskRepository.latestQuote(symbol),
    ) { stories, events, tiers, quote ->
        val now = clock()
        SymbolUiState(
            symbol = symbol,
            summary = SymbolTimeline.summarize(symbol, stories, events, now),
            sections = SymbolTimeline.sections(stories, now),
            events = events,
            tier = tiers[symbol],
            quote = quote,
            nowMillis = now,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SymbolUiState(symbol = symbol))

    fun setTier(tier: WatchTier?) {
        viewModelScope.launch {
            try {
                if (tier == null) repository.toggleWatchlist(symbol) else repository.setWatchTier(symbol, tier)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tier is a preference, not data; a failed write is not worth a banner.
            }
        }
    }

    companion object {
        /**
         * Keyed by symbol at the call site, so navigating from one company to another
         * builds a new view model rather than silently reusing the first one's flows.
         */
        fun factory(
            repository: NewsRepository,
            deskRepository: DeskRepository,
            symbol: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer<SymbolViewModel> { SymbolViewModel(repository, deskRepository, symbol) }
        }

    }
}
