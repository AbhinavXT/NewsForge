package com.abhinavxt.newsforge.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.desk.BoardSnapshot
import com.abhinavxt.newsforge.core.desk.Boards
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.data.DeskPreferences
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.ticks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The newest story on one name, beside its row on the board. */
data class BoardHeadline(val title: String, val publishedAt: Long, val clusterId: String)

data class BoardUiState(
    val configured: Boolean = true,
    val loaded: Boolean = false,
    val board: BoardSnapshot? = null,
    val headlines: Map<String, BoardHeadline> = emptyMap(),
    val followed: Set<String> = emptySet(),
    /** A request is out and its reply has not landed yet. */
    val requesting: Boolean = false,
    val error: String? = null,
    val nowMillis: Long = System.currentTimeMillis(),
)

/**
 * The desk's order-block board, beside the news on each name.
 *
 * Joins the two repositories at the point of display and nowhere else, as the feed does:
 * the board never enters the article store, and stories never reach the desk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModel(
    private val desk: DeskRepository,
    private val news: NewsRepository,
    private val preferences: DeskPreferences,
) : ViewModel() {

    private val requesting = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val board = desk.latestBoard()

    private val headlines = board
        .map { it?.symbols.orEmpty().distinct().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { symbols ->
            if (symbols.isEmpty()) flowOf(emptyList()) else news.stories(symbols)
        }
        .map { stories -> newestPerSymbol(stories) }

    val uiState: StateFlow<BoardUiState> = combine(
        combine(board, headlines, ::Pair),
        news.watchlist().map { it.toSet() },
        requesting,
        error,
        ticks(CLOCK_TICK_MS, System::currentTimeMillis),
    ) { (board, headlines), followed, requesting, error, now ->
        BoardUiState(
            configured = preferences.isConfigured,
            loaded = true,
            board = board,
            headlines = headlines,
            followed = followed,
            requesting = requesting,
            error = error,
            nowMillis = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardUiState())

    init {
        // On open, when what is held is old enough to have changed. The desk rebuilds its
        // board at most once a minute, so asking more often only repeats the last one.
        viewModelScope.launch {
            val held = board.first()
            val age = held?.takenAtMillis?.let { System.currentTimeMillis() - it }
            if (preferences.isConfigured && (age == null || age > STALE_ON_OPEN_MS)) refresh()
        }
    }

    /** Asks the desk for its board, then polls through the time it takes to answer. */
    fun refresh() {
        if (requesting.value) return
        requesting.value = true
        viewModelScope.launch {
            try {
                val failure = desk.send(Boards.COMMAND)
                error.value = failure
                if (failure == null) {
                    // Building the board means a quote call across the F&O list, which
                    // takes the desk a few seconds; a single immediate read would always
                    // miss it.
                    for (wait in REPLY_POLL_MS) {
                        delay(wait)
                        desk.sync()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Could not reach the desk"
            } finally {
                requesting.value = false
            }
        }
    }

    companion object {
        private const val CLOCK_TICK_MS = 30_000L
        private const val STALE_ON_OPEN_MS = 2L * 60 * 1000
        private val REPLY_POLL_MS = longArrayOf(2_000L, 3_000L, 4_000L, 6_000L)

        /** The newest story per symbol it mentions, among [stories]. */
        internal fun newestPerSymbol(stories: List<ScoredArticle>): Map<String, BoardHeadline> {
            val out = HashMap<String, BoardHeadline>()
            for (scored in stories.sortedByDescending { it.article.publishedAt }) {
                val article = scored.article
                for (symbol in article.symbols) {
                    out.putIfAbsent(
                        symbol,
                        BoardHeadline(article.title, article.publishedAt, article.clusterId),
                    )
                }
            }
            return out
        }

        fun factory(
            desk: DeskRepository,
            news: NewsRepository,
            preferences: DeskPreferences,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BoardViewModel(desk, news, preferences) }
        }
    }
}
