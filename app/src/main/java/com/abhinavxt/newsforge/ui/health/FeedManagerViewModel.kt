package com.abhinavxt.newsforge.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.data.db.FeedEntity
import com.abhinavxt.newsforge.data.db.FeedStateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A configured feed and what happened the last time it was polled. */
data class FeedHealth(
    val feed: FeedEntity,
    val state: FeedStateEntity?,
) {
    val hasEverSucceeded: Boolean get() = state?.lastSuccessAt != null
    val isFailing: Boolean get() = (state?.consecutiveFailures ?: 0) > 0
}

data class FeedManagerUiState(
    val feeds: List<FeedHealth> = emptyList(),
    val mutes: List<MuteRule> = emptyList(),
    /** Result of the last one-off fetch triggered from the editor. */
    val testResult: String? = null,
)

class FeedManagerViewModel(
    private val repository: NewsRepository,
) : ViewModel() {

    private val testResult = MutableStateFlow<String?>(null)

    /**
     * Driven off the configured-feed table joined to polling state, not off stored rows.
     *
     * A feed that has never once returned anything has no state row at all, and that is
     * precisely the failure this screen exists to make visible.
     */
    val uiState: StateFlow<FeedManagerUiState> =
        combine(
            repository.configuredFeeds(),
            repository.feedHealth(),
            repository.muteRules(),
            testResult,
        ) { feeds, states, mutes, test ->
            val byId = states.associateBy { it.feedId }
            FeedManagerUiState(feeds.map { FeedHealth(it, byId[it.id]) }, mutes, test)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedManagerUiState())

    init {
        // Populates the table on first open so the screen is never empty on a fresh
        // install, even before the first sync has run.
        viewModelScope.launch {
            runCatching { repository.ensureFeedsSeeded() }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = launchQuietly {
        repository.setFeedEnabled(id, enabled)
    }

    fun save(feed: FeedEntity) = launchQuietly { repository.saveFeed(feed) }

    fun reset(id: String) = launchQuietly { repository.resetFeed(id) }

    fun removeMute(rule: MuteRule) = launchQuietly { repository.removeMute(rule) }

    fun delete(id: String) = launchQuietly { repository.deleteFeed(id) }

    /** Fetches once and reports what came back, without storing anything. */
    fun test(url: String, kind: FeedKind = FeedKind.RSS) {
        testResult.value = "Testing…"
        viewModelScope.launch {
            testResult.value = try {
                repository.testFeed(url, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Test failed"
            }
        }
    }

    fun clearTest() {
        testResult.value = null
    }

    private fun launchQuietly(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                testResult.value = e.message ?: "Could not save"
            }
        }
    }

    companion object {
        fun factory(repository: NewsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedManagerViewModel(repository) }
        }
    }
}
