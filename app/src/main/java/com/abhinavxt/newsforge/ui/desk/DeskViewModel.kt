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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeskUiState(
    val messages: List<DeskMessage> = emptyList(),
    val unread: Int = 0,
    val server: String = DeskPreferences.DEFAULT_SERVER,
    val topic: String = "",
    val token: String = "",
    val configured: Boolean = false,
    val error: String? = null,
    val testResult: String? = null,
    val nowMillis: Long = 0L,
)

class DeskViewModel(
    private val repository: DeskRepository,
    private val preferences: DeskPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)
    private val testResult = MutableStateFlow<String?>(null)
    /** Bumped on save so the flows re-read SharedPreferences, which do not emit. */
    private val settingsRevision = MutableStateFlow(0)

    val uiState: StateFlow<DeskUiState> = combine(
        repository.recent(),
        repository.unreadCount(),
        error,
        testResult,
        settingsRevision,
    ) { messages, unread, currentError, test, _ ->
        DeskUiState(
            messages = messages,
            unread = unread,
            server = preferences.server,
            topic = preferences.topic,
            token = preferences.token,
            configured = preferences.isConfigured,
            error = currentError,
            testResult = test,
            nowMillis = clock(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeskUiState())

    fun save(server: String, topic: String, token: String) {
        preferences.server = server
        preferences.topic = topic
        preferences.token = token
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

    fun markAllRead() {
        viewModelScope.launch { runCatching { repository.markAllRead() } }
    }

    companion object {
        fun factory(
            repository: DeskRepository,
            preferences: DeskPreferences,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeskViewModel(repository, preferences) }
        }
    }
}
