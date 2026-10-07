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
import com.abhinavxt.newsforge.core.world.Keywords
import com.abhinavxt.newsforge.data.SavedArticles
import com.abhinavxt.newsforge.data.WorldPreferences
import com.abhinavxt.newsforge.data.WorldSettings
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.core.rank.PollingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /** Only stories mentioning a followed keyword. */
    val followingOnly: Boolean = false,
    val query: String = "",
) {
    val isNarrowed: Boolean
        get() = topic != null || unreadOnly || savedOnly || followingOnly || query.isNotBlank()
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
        /**
         * Stories mentioning a followed keyword, ranked, each with the keyword it matched.
         * Unaffected by the topic chip: following "ISRO" means wanting it whichever topic
         * the story was filed under.
         */
        val following: List<Pair<ScoredArticle, String>>,
        val settings: WorldSettings,
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
    private val preferences: WorldPreferences,
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
        preferences.settings,
    ) { all, filter, refreshing, error, settings ->
        if (all == null) return@combine WorldUiState.Loading
        // A hidden topic is gone everywhere — chips, sections, search — except when it is
        // the topic explicitly selected, which only happens as it is being hidden.
        val shown = all.filter { it.article.category !in settings.hidden }
        val matched = shown.mapNotNull { scored ->
            Keywords.firstMatch(settings.keywords, scored.article.title, scored.article.summary)
                ?.let { scored to it }
        }
        val matchedIds = matched.mapTo(HashSet()) { it.first.article.clusterId }
        val beforeTopic = shown.filter { scored ->
            val article = scored.article
            (!filter.unreadOnly || !article.read) &&
                (!filter.savedOnly || article.saved) &&
                (!filter.followingOnly || article.clusterId in matchedIds)
        }
        WorldUiState.Ready(
            stories = beforeTopic.filter { filter.topic == null || it.article.category == filter.topic },
            topicCounts = settings.visibleTopics.associateWith { topic ->
                beforeTopic.count { it.article.category == topic }
            },
            following = matched,
            settings = settings,
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

    fun toggleFollowingOnly() = filter.update { it.copy(followingOnly = !it.followingOnly) }

    fun setTopicHidden(topic: Category, hidden: Boolean) {
        preferences.setHidden(topic, hidden)
        // Hiding the selected topic would leave an empty list with no chip to clear it.
        if (hidden) filter.update { if (it.topic == topic) it.copy(topic = null) else it }
    }

    fun moveTopic(topic: Category, by: Int) = preferences.move(topic, by)

    fun resetTopics() = preferences.resetTopics()

    /** @return false when the keyword was rejected, so the field can say so. */
    fun addKeyword(keyword: String): Boolean = preferences.addKeyword(keyword)

    fun removeKeyword(keyword: String) = preferences.removeKeyword(keyword)

    fun setKeywordAlerts(enabled: Boolean) = preferences.setKeywordAlerts(enabled)

    fun setDigestEnabled(enabled: Boolean) = preferences.setDigestEnabled(enabled)

    fun setDigestHour(hour: Int) = preferences.setDigestHour(hour)

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun clearFilters() = filter.update { WorldFilter() }

    fun refresh(minGapMillis: Long = 0L) {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val report = repository.refresh(minGapMillis, desks = setOf(Desk.WORLD))
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

    /**
     * Keeps the world feeds fresh while the tab is on screen.
     *
     * Runs only while the World tab is composed and the app is in front, so it costs
     * nothing on the market tabs. On arrival it refreshes straight away if the last world
     * sync is older than one interval — opening the tab in the evening should not show
     * the afternoon's news for another ten minutes.
     */
    suspend fun autoRefreshWhileVisible() {
        val interval = PollingPolicy.WORLD_FOREGROUND_MS
        if (clock() - repository.lastSyncedAt(Desk.WORLD) >= interval) refresh(minGapMillis = interval)
        while (currentCoroutineContext().isActive) {
            delay(interval)
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
            preferences: WorldPreferences,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorldViewModel(repository, savedArticles, preferences) }
        }
    }
}
