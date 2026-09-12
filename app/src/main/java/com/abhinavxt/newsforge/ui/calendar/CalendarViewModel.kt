package com.abhinavxt.newsforge.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.calendar.CalendarGrouping
import com.abhinavxt.newsforge.core.calendar.CalendarSection
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.data.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CalendarUiState(
    val sections: List<CalendarSection> = emptyList(),
    val tiers: Map<String, WatchTier> = emptyMap(),
    val followedOnly: Boolean = false,
    val total: Int = 0,
    val nseEnabled: Boolean = false,
    val nowMillis: Long = 0L,
)

class CalendarViewModel(
    repository: NewsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val followedOnly = MutableStateFlow(false)

    val uiState: StateFlow<CalendarUiState> = combine(
        repository.upcomingEvents(),
        repository.watchlistTiers(),
        followedOnly,
        repository.configuredFeeds(),
    ) { events, tiers, mine, feeds ->
        val now = clock()
        CalendarUiState(
            sections = CalendarGrouping.group(events, now, tiers.keys, mine),
            tiers = tiers,
            followedOnly = mine,
            // Counted before the "mine only" filter, so the header never implies the
            // calendar is emptier than it is.
            total = CalendarGrouping.group(events, now).sumOf { it.events.size },
            nseEnabled = feeds.any { it.enabled && it.kind != FeedKind.RSS.name },
            nowMillis = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun toggleFollowedOnly() {
        followedOnly.value = !followedOnly.value
    }

    companion object {
        fun factory(repository: NewsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CalendarViewModel(repository) }
        }
    }
}
