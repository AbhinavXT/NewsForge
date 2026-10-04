package com.abhinavxt.newsforge.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.reader.Mentions
import com.abhinavxt.newsforge.core.reader.ReaderSettings
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.core.tag.SymbolSpan
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.ReaderPreferences
import com.abhinavxt.newsforge.data.ReaderRepository
import com.abhinavxt.newsforge.data.ReaderResult
import com.abhinavxt.newsforge.data.ReadingPosition
import com.abhinavxt.newsforge.data.SavedArticles
import com.abhinavxt.newsforge.data.db.CoverageRow
import com.abhinavxt.newsforge.data.model.ArticleSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the reader needs, bundled so the app shell passes one thing. */
class ReaderDeps(
    val reader: ReaderRepository,
    val news: NewsRepository,
    val saved: SavedArticles,
    val preferences: ReaderPreferences,
    val lexicon: () -> SymbolLexicon,
)

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    /**
     * @param position where to open the article, when it was read before.
     * @param mentions per block, the company names to make tappable.
     */
    data class Done(
        val result: ReaderResult,
        val position: ReadingPosition? = null,
        val mentions: Map<Int, List<SymbolSpan>> = emptyMap(),
    ) : ReaderUiState
}

/**
 * What the feed knows about the story behind a link.
 *
 * Null [story] for a link the feed never carried — a desk message's, say — which reads
 * fine as an article and simply has no companies, price or coverage to show.
 */
data class StoryContext(
    val story: ArticleSummary? = null,
    /** Other outlets' versions, this one excluded. */
    val coverage: List<CoverageRow> = emptyList(),
)

/** One article in reader form. Keyed by URL, so each story gets its own instance. */
class ReaderViewModel(
    private val deps: ReaderDeps,
    val url: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _context = MutableStateFlow(StoryContext())
    val context: StateFlow<StoryContext> = _context.asStateFlow()

    private val _settings = MutableStateFlow(deps.preferences.settings)
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    val offline: StateFlow<Boolean> = deps.reader.offlineUrls()
        .map { url in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loading: Job? = null

    init {
        load()
        viewModelScope.launch { loadContext() }
    }

    fun retry() {
        if (loading?.isActive == true) return
        load()
    }

    fun updateSettings(next: ReaderSettings) {
        _settings.value = next
        deps.preferences.settings = next
    }

    /** Saving keeps an offline copy; unsaving drops it. See [SavedArticles]. */
    fun toggleSaved() {
        val story = _context.value.story ?: return
        viewModelScope.launch {
            try {
                deps.saved.setSaved(story.id, !story.saved)
                loadContext()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The star stays where it was, which says the save did not happen.
            }
        }
    }

    /**
     * Records where the reader is.
     *
     * Called as they scroll, so it is cheap and never reported: losing a position costs
     * someone a scroll, and an error for that would be out of all proportion.
     */
    fun savePosition(position: ReadingPosition) {
        // Kept here as well as stored: this view model outlives the screen, and coming
        // back to the article reads the position from here rather than from the table.
        (_uiState.value as? ReaderUiState.Done)?.let { _uiState.value = it.copy(position = position) }
        viewModelScope.launch {
            try {
                deps.reader.savePosition(url, position)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun load() {
        _uiState.value = ReaderUiState.Loading
        loading = viewModelScope.launch {
            // Read first, so the article opens where it was left rather than jumping
            // there a frame after drawing the top.
            val position = try {
                deps.reader.position(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val result = deps.reader.load(url)
            val mentions = (result as? ReaderResult.Ready)?.let { ready ->
                val tagged = try {
                    deps.news.storyForLink(url)?.symbols.orEmpty().toSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }
                withContext(Dispatchers.Default) {
                    val lexicon = deps.lexicon()
                    Mentions.firstMentions(ready.article.blocks, tagged) { lexicon.spans(it) }
                }
            }.orEmpty()
            _uiState.value = ReaderUiState.Done(result, position, mentions)
        }
    }

    private suspend fun loadContext() {
        try {
            val story = deps.news.storyForLink(url)
            val coverage = story?.let { deps.news.coverage(it.clusterId) }.orEmpty()
                .filter { it.link != url }
            _context.value = StoryContext(story, coverage)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Context is extra. The article reads the same without it.
        }
    }

    companion object {
        fun factory(deps: ReaderDeps, url: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ReaderViewModel(deps, url) }
            }
    }
}
