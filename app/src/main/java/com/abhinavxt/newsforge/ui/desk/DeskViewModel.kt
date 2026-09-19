package com.abhinavxt.newsforge.ui.desk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.data.DeskPreferences
import com.abhinavxt.newsforge.data.DeskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeskUiState(
    val messages: List<DeskMessage> = emptyList(),
    val unread: Int = 0,
    val server: String = DeskPreferences.DEFAULT_SERVER,
    val topic: String = "",
    /** Where commands go, when that differs from where replies arrive. */
    val commandTopic: String = "",
    val token: String = "",
    val configured: Boolean = false,
    val error: String? = null,
    val testResult: String? = null,
    val nowMillis: Long = 0L,
    /** Lines sent before, newest first, for one-tap reuse. */
    val recentCommands: List<String> = emptyList(),
    /** True while a line is in flight, so the field can be held. */
    val sending: Boolean = false,
)

class DeskViewModel(
    private val repository: DeskRepository,
    private val preferences: DeskPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)
    private val testResult = MutableStateFlow<String?>(null)
    private val sending = MutableStateFlow(false)
    /** Bumped on save so the flows re-read SharedPreferences, which do not emit. */
    private val settingsRevision = MutableStateFlow(0)

    val uiState: StateFlow<DeskUiState> = combine(
        repository.recent(),
        repository.unreadCount(),
        error,
        testResult,
        combine(settingsRevision, sending) { _, isSending -> isSending },
    ) { messages, unread, currentError, test, isSending ->
        DeskUiState(
            messages = messages,
            unread = unread,
            server = preferences.server,
            topic = preferences.topic,
            commandTopic = preferences.commandTopic,
            token = preferences.token,
            configured = preferences.isConfigured,
            error = currentError,
            testResult = test,
            nowMillis = clock(),
            recentCommands = preferences.recentCommands,
            sending = isSending,
        )
    }
        // Also keeps the SharedPreferences reads above off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeskUiState())

    fun save(server: String, topic: String, token: String, commandTopic: String) {
        preferences.server = server
        preferences.topic = topic
        preferences.token = token
        preferences.commandTopic = commandTopic
        settingsRevision.value += 1
        // Pull immediately: having just entered a topic, an empty list would look like a
        // failure rather than a wait for the next sync.
        refresh()
    }

    fun test(server: String, topic: String, token: String) {
        testResult.value = "Testing…"
        viewModelScope.launch {
            testResult.value = try {
                repository.test(server, topic, token)
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

    fun refresh() {
        viewModelScope.launch {
            error.value = try {
                repository.sync().error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Bridge poll failed"
            }
        }
    }

    /**
     * Polls the bridge while the desk is on screen.
     *
     * The desk had no foreground polling at all: it refreshed when the workers happened
     * to run — every fifteen minutes, or every minute only during a market session — so
     * sitting on the tab in the evening showed a list that did not move, and the Refresh
     * button was the only way to find out anything had happened.
     *
     * Faster than the feed's because the desk is now a conversation, not a wire. A reply
     * to a command that takes two minutes to appear reads as the command having failed,
     * and the person who typed it is by definition looking at the screen.
     *
     * Runs on the STARTED lifecycle, so it stops when the app is backgrounded and the
     * workers take over.
     */
    suspend fun autoRefreshWhileVisible() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            if (preferences.isConfigured) refresh()
        }
    }

    fun markAllRead() {
        viewModelScope.launch { runCatching { repository.markAllRead() } }
    }

    /**
     * Sends a line to the desk and immediately polls for the answer.
     *
     * The poll is the point. Without it a command sits there looking ignored until the
     * next scheduled sync minutes later, which for something that reads like a terminal
     * is indistinguishable from it not having worked.
     */
    fun send(text: String) {
        val line = text.trim()
        if (line.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                val failure = repository.send(line)
                error.value = failure
                if (failure == null) {
                    // Remembered only once it has actually gone, so a typo that the
                    // bridge rejected does not get offered back as a suggestion.
                    preferences.rememberCommand(line)
                    settingsRevision.value++
                    // A burst rather than a single poll. The desk has to receive the
                    // command, act on it and publish, and one immediate read almost
                    // always lands before any of that — leaving the reply to surface
                    // twenty seconds later as though nothing had happened.
                    for (wait in REPLY_POLL_MS) {
                        repository.sync()
                        delay(wait)
                    }
                    repository.sync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Could not send that"
            } finally {
                sending.value = false
            }
        }
    }

    companion object {
        /**
         * Gap between foreground polls.
         *
         * ntfy's `poll=1` read is a single cheap request against a watermark, so the cost
         * is a round trip rather than a payload — but it is still a radio wake every
         * fifteen seconds, which is only worth it because this runs solely while somebody
         * is looking at the tab.
         */
        private const val POLL_INTERVAL_MS = 15_000L

        /**
         * Waits after sending, before each follow-up read.
         *
         * Front-loaded on purpose: most replies are immediate and a first read at two
         * seconds catches them, while the later ones exist for a desk that has to hit an
         * API before it can answer. Nine seconds of attention in total, after which the
         * ordinary interval takes over.
         */
        private val REPLY_POLL_MS = longArrayOf(2_000L, 3_000L, 4_000L)

        fun factory(
            repository: DeskRepository,
            preferences: DeskPreferences,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeskViewModel(repository, preferences) }
        }
    }
}
