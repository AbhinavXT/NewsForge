package com.abhinavxt.newsforge.ui.health

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.backup.FeedMergeResult
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.data.db.FeedEntity
import com.abhinavxt.newsforge.data.db.FeedStateEntity
import com.abhinavxt.newsforge.data.net.HttpCache
import com.abhinavxt.newsforge.data.tag.InstrumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

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
    /** Result of the last backup or restore. */
    val backupMessage: String? = null,
    /** Result of the last company-list refresh. */
    val lexiconMessage: String? = null,
)

class FeedManagerViewModel(
    private val repository: NewsRepository,
    private val instruments: InstrumentRepository,
    /**
     * Reaches the file the person picked.
     *
     * A content URI, not a path — the document picker hands back a grant to one file
     * wherever they keep things, which is why this needs no storage permission and works
     * across a reinstall.
     */
    private val resolver: ContentResolver,
) : ViewModel() {

    private val testResult = MutableStateFlow<String?>(null)
    private val backupMessage = MutableStateFlow<String?>(null)
    private val lexiconMessage = MutableStateFlow<String?>(null)

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
            combine(backupMessage, lexiconMessage) { backup, lexicon -> backup to lexicon },
        ) { feeds, states, mutes, test, messages ->
            val byId = states.associateBy { it.feedId }
            FeedManagerUiState(
                feeds = feeds.map { FeedHealth(it, byId[it.id]) },
                mutes = mutes,
                testResult = test,
                backupMessage = messages.first,
                lexiconMessage = messages.second,
            )
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

    /**
     * Pulls the company list now.
     *
     * Forced, unlike the weekly refresh on the sync path: somebody pressing this has just
     * noticed a story that was not tagged and wants it fixed before the next Tuesday.
     */
    fun refreshCompanyList() {
        viewModelScope.launch {
            lexiconMessage.value = "Fetching the NSE company list…"
            lexiconMessage.value = try {
                val result = instruments.refresh(force = true)
                when {
                    result == null -> "Company list is already current"
                    result.error != null -> result.error
                    else -> "${result.symbols} companies known"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Could not refresh the company list"
            }
        }
    }

    fun clearBackupMessage() {
        backupMessage.value = null
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            backupMessage.value = try {
                val text = repository.exportFeeds()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: throw IOException("Could not write to the file you chose")
                }
                "Feed list backed up"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Backup failed"
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            backupMessage.value = try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        // Bounded, because the file came from a picker and could be
                        // anything at all. Reusing the feed reader's limit rather than
                        // inventing a second one, and refusing beats truncating for the
                        // same reason there: half a backup parses as a shorter backup.
                        HttpCache.readBounded(stream, MAX_BACKUP_BYTES)
                            ?: throw IOException("That file is too large to be a feed backup")
                    } ?: throw IOException("Could not read the file you chose")
                    bytes.toString(Charsets.UTF_8)
                }
                summarise(repository.importFeeds(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Restore failed"
            }
        }
    }

    /**
     * Says what actually happened rather than "done".
     *
     * A restore that changed nothing and a restore that added eleven feeds both succeed,
     * and the difference is the entire reason for running it.
     */
    private fun summarise(result: FeedMergeResult): String {
        val parts = buildList {
            if (result.added > 0) add("${result.added} added")
            if (result.updated > 0) add("${result.updated} updated")
            if (result.unchanged > 0) add("${result.unchanged} already matched")
            if (result.skipped > 0) add("${result.skipped} skipped")
        }
        return if (parts.isEmpty()) "Nothing in that file to restore" else parts.joinToString(", ")
    }

    companion object {
        /** Generous for a feed list, small enough that a mis-picked video fails fast. */
        private const val MAX_BACKUP_BYTES = 2L * 1024 * 1024

        fun factory(
            repository: NewsRepository,
            instruments: InstrumentRepository,
            resolver: ContentResolver,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedManagerViewModel(repository, instruments, resolver) }
        }
    }

}
