@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package com.abhinavxt.newsforge.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.Desk
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.SavedArticles
import com.abhinavxt.newsforge.data.model.ScoredArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the World tab is narrowed to. Every field composes with the others. */
data class WorldFilter(
    /** One topic, or every topic when null. */
    val topic: Category? = null,
    val unreadOnly: Boolean = false,
    val savedOnly: Boolean = false,
    val query: String = "",
) {
    val isNarrowed: Boolean
        get() = topic != null || unreadOnly || savedOnly || query.isNotBlank()
}

sealed interface WorldUiState {

    data object Loading : WorldUiState

    /**
     * @param stories filtered and ranked.
     * @param topicCounts per topic before the topic chip is applied, so a chip never reads
     *   zero merely because another topic is selected.
     */
    data class Ready(
        val stories: List<ScoredArticle>,
        val topicCounts: Map<Category, Int>,
        val filter: WorldFilter,
        val refreshing: Boolean,
        val lastError: String?,
        val nowMillis: Long,
    ) : WorldUiState
}

/**
 * The World tab: general news, kept apart from the market feed.
 *
 * Deliberately smaller than the feed's view model. Nothing here has a ticker, a price or
 * an alert rule, so the watchlist, quotes, desk and mute machinery that the market feed
 * carries would be weight with nothing to do.
 *
 * Refreshing runs the same sync as the market feed — one pass over every enabled feed —
 * because there is one store and one refresh lock, and two tabs polling the network on
 * their own schedules would only fetch the same documents twice.
 */
class WorldViewModel(
    private val repository: NewsRepository,
    private val savedArticles: SavedArticles,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val filter = MutableStateFlow(WorldFilter())
    private val refreshing = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)

    private val stories: StateFlow<List<ScoredArticle>?> =
        filter
            .map { it.query.trim() }
            .distinctUntilChanged()
            .debounce { text -> if (text.length >= NewsRepository.MIN_SEARCH_CHARS) TYPING_PAUSE_MS else 0L }
            .flatMapLatest { text ->
                if (text.length >= NewsRepository.MIN_SEARCH_CHARS) repository.search(text, Desk.WORLD)
                else repository.worldStories()
            }
            .map<List<ScoredArticle>, List<ScoredArticle>?> { it }
            .catch { e ->
                if (e is CancellationException) throw e
                lastError.value = e.message ?: "Could not read stored news"
                emit(emptyList())
            }
            // Null until the first read lands, so the screen shows a skeleton rather than
            // an empty state that flashes "nothing here" on every launch.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val uiState: StateFlow<WorldUiState> = combine(
        stories,
        filter,
        refreshing,
        lastError,
    ) { all, filter, refreshing, error ->
        if (all == null) return@combine WorldUiState.Loading
        val beforeTopic = all.filter { scored ->
            val article = scored.article
            (!filter.unreadOnly || !article.read) && (!filter.savedOnly || article.saved)
        }
        WorldUiState.Ready(
            stories = beforeTopic.filter { filter.topic == null || it.article.category == filter.topic },
            topicCounts = Category.WORLD_TOPICS.associateWith { topic ->
                beforeTopic.count { it.article.category == topic }
            },
            filter = filter,
            refreshing = refreshing,
            lastError = error,
            nowMillis = clock(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WorldUiState.Loading)

    /** Re-selecting the active topic clears it, the same as the market feed's chips. */
    fun selectTopic(topic: Category?) =
        filter.update { it.copy(topic = if (it.topic == topic) null else topic) }

    fun toggleUnreadOnly() = filter.update { it.copy(unreadOnly = !it.unreadOnly) }

    fun toggleSavedOnly() = filter.update { it.copy(savedOnly = !it.savedOnly) }

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun clearFilters() = filter.update { WorldFilter() }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val report = repository.refresh()
                lastError.value = when {
                    report.allFailed -> "No feeds reachable — check the connection"
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
                savedArticles.setSaved(articleId, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: "Could not update saved state"
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TYPING_PAUSE_MS = 250L

        fun factory(
            repository: NewsRepository,
            savedArticles: SavedArticles,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorldViewModel(repository, savedArticles) }
        }
    }
}
