package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.desk.NtfyMessages
import com.abhinavxt.newsforge.data.db.DeskDao
import com.abhinavxt.newsforge.data.db.DeskMessageEntity
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.NtfyJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Outcome of one bridge poll, for the Desk screen's status line. */
data class DeskSyncResult(
    val fetched: Int,
    val inserted: Int,
    val error: String?,
) {
    companion object {
        val NotConfigured = DeskSyncResult(0, 0, null)
    }
}

/**
 * The bridge to your own machine.
 *
 * A separate repository, not a corner of [NewsRepository], and it shares nothing but the
 * HTTP client. Alerts from TickerForge never enter the article store, never reach the news
 * ranker and never appear in the brief.
 */
class DeskRepository(
    private val deskDao: DeskDao,
    private val fetcher: FeedFetcher,
    private val preferences: DeskPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun recent(limit: Int = 200): Flow<List<DeskMessage>> =
        deskDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    /**
     * The most recent quote for one symbol, if it is still fresh.
     *
     * This is what puts the tape next to the headline. Staleness is enforced here rather
     * than at the call site so no screen can accidentally render a twenty-minute-old price
     * as live — which is the exact mistake the feature exists to prevent.
     */
    fun latestQuote(symbol: String, maxAgeMillis: Long = DeskPayload.DEFAULT_STALE_MS): Flow<DeskPayload?> =
        deskDao.observeRecent(QUOTE_SCAN_LIMIT).map { rows ->
            val wanted = symbol.uppercase()
            rows.asSequence()
                .mapNotNull { it.toDomain().payload }
                .filter { it.symbol == wanted && it.ltp != null }
                .firstOrNull { !it.isStale(clock(), maxAgeMillis) }
        }

    fun unreadCount(): Flow<Int> = deskDao.observeUnreadCount()

    suspend fun markAllRead() = withContext(ioDispatcher) { deskDao.markAllRead() }

    suspend fun sync(): DeskSyncResult = withContext(ioDispatcher) {
        if (!preferences.isConfigured) return@withContext DeskSyncResult.NotConfigured

        // Re-ask from slightly before the newest message we hold. ntfy's `since` is
        // inclusive and ids are the real dedupe, so a small overlap costs one duplicate
        // frame and protects against a message landing in the same second as the last poll.
        val watermark = deskDao.latestReceivedAt()?.let { (it / 1000) - OVERLAP_SECONDS }
        val url = NtfyMessages.pollUrl(preferences.server, preferences.topic, watermark)

        val result = try {
            fetcher.fetch(
                url = url,
                validators = null,
                bearer = preferences.token.takeIf { it.isNotBlank() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DeskSyncResult(0, 0, e.message ?: "Bridge poll failed")
        }

        when (result) {
            is FetchResult.Failure -> DeskSyncResult(
                0, 0,
                if (result.status > 0) "HTTP ${result.status}" else result.message,
            )

            is FetchResult.NotModified -> DeskSyncResult(0, 0, null)

            is FetchResult.Success -> {
                val messages = NtfyJson.parse(result.bytes, clock())
                val inserted = if (messages.isEmpty()) {
                    0
                } else {
                    deskDao.insertAll(messages.map { it.toEntity() }).count { it != -1L }
                }
                deskDao.pruneOlderThan(clock() - KEEP_MS)
                Log.i(TAG, "desk: ${messages.size} fetched, $inserted new")
                DeskSyncResult(messages.size, inserted, null)
            }
        }
    }

    /** Fetches once against untried settings and reports what happened. */
    suspend fun test(server: String, topic: String, token: String): String =
        withContext(ioDispatcher) {
            if (topic.isBlank()) return@withContext "Enter a topic first"
            val url = NtfyMessages.pollUrl(server, topic, null)
            when (val result = fetcher.fetch(url, null, bearer = token.takeIf { it.isNotBlank() })) {
                is FetchResult.Failure ->
                    if (result.status > 0) "HTTP ${result.status}: ${result.message}" else result.message

                is FetchResult.NotModified -> "Reachable"
                is FetchResult.Success -> {
                    val count = NtfyJson.parse(result.bytes, clock()).size
                    // An empty topic is reachable and correct; ntfy simply has nothing
                    // queued, which is not an error worth reporting as one.
                    if (count > 0) "OK — $count messages" else "Connected, nothing queued"
                }
            }
        }

    private companion object {
        const val TAG = "DeskRepository"
        const val OVERLAP_SECONDS = 5L

        /** Quotes arrive often; scanning the newest slice is enough to find one. */
        const val QUOTE_SCAN_LIMIT = 60

        /** Your own alerts are worth keeping longer than general news. */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun DeskMessage.toEntity() = DeskMessageEntity(
    id = id,
    topic = topic,
    title = title,
    body = body,
    priority = priority,
    tags = tags.joinToString(","),
    clickUrl = clickUrl,
    receivedAt = receivedAtMillis,
)

private fun DeskMessageEntity.toDomain(): DeskMessage = DeskMessage(
    id = id,
    topic = topic,
    title = title,
    body = body,
    priority = priority,
    tags = tags.split(',').filter { it.isNotBlank() },
    clickUrl = clickUrl,
    receivedAtMillis = receivedAt,
    payload = com.abhinavxt.newsforge.data.net.NtfyJson.parsePayload(body),
)
