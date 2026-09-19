package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.desk.NtfyMessages
import com.abhinavxt.newsforge.core.desk.PositionSnapshot
import com.abhinavxt.newsforge.core.desk.ScreenResult
import com.abhinavxt.newsforge.data.db.DeskDao
import com.abhinavxt.newsforge.data.db.DeskMessageEntity
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.NtfyJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Outcome of one bridge poll, for the Desk screen's status line. */
data class DeskSyncResult(
    val fetched: Int,
    val inserted: Int,
    val error: String?,
    /** Messages stored by this sync. Empty on a poll that found nothing new. */
    val stored: List<DeskMessage> = emptyList(),
    /** Screener output in this batch, newest run per screen. Applied by the caller. */
    val screens: List<ScreenResult> = emptyList(),
    /**
     * The newest exposure snapshot in this batch, if the desk sent one.
     *
     * Returned rather than applied here. This repository deliberately shares nothing with
     * the news store beyond an HTTP client, and reaching into the watchlist from inside
     * it would be the first thread of exactly the coupling that separation exists to
     * prevent. The caller decides.
     */
    val positions: PositionSnapshot? = null,
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
    /** Quotes pushed by the desk are written down too; see [PriceHistoryRepository]. */
    private val history: PriceHistoryRepository? = null,
    /**
     * Price bars pushed by the desk, stored here rather than handed back.
     *
     * Applied inline for the same reason [history] is, and returning them instead was a
     * real bug rather than a stylistic one: [DeskSyncResult.stored] holds only the rows
     * this poll actually inserted, so whichever caller polls first is the only one that
     * ever sees a given message. The desk tab polls every few seconds while it is open
     * and again in a burst after a command — so it always won that race and always
     * discarded the result, while the worker that knew what to do with candles arrived
     * fifteen minutes later to an empty list.
     *
     * Unlike positions and screens, this reaches nothing the news store owns, so there is
     * no separation being given up by doing it here.
     */
    private val candles: CandleRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where rows are decoded. Every message body is re-parsed as JSON on each emission. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * The desk log, as a person would want to read it.
     *
     * Machine payloads are dropped here rather than at the screen, because every consumer
     * of this wants the same thing: candle batches are stored by [sync] on the way past
     * and have no business in a list of messages. Reading over the limit first so that
     * filtering does not quietly shorten the log — five candle messages should not cost
     * five signals off the bottom.
     */
    fun recent(limit: Int = 200): Flow<List<DeskMessage>> =
        deskDao.observeRecent(limit * 2)
            .map { rows -> rows.map { it.toDomain() }.filterNot { it.machine }.take(limit) }
            .flowOn(computeDispatcher)

    /**
     * The most recent quote for one symbol, if it is still fresh.
     *
     * This is what puts the tape next to the headline. Staleness is enforced here rather
     * than at the call site so no screen can accidentally render a twenty-minute-old price
     * as live — which is the exact mistake the feature exists to prevent.
     */
    fun latestQuote(symbol: String, maxAgeMillis: Long = DeskPayload.DEFAULT_STALE_MS): Flow<DeskPayload?> =
        combine(
            deskDao.observeRecent(QUOTE_SCAN_LIMIT),
            ticks(FRESHNESS_TICK_MS, clock),
        ) { rows, now ->
            val wanted = symbol.uppercase()
            rows.asSequence()
                .flatMap { it.toDomain().payloads.asSequence() }
                .filter { it.symbol == wanted && it.ltp != null }
                .firstOrNull { !it.isStale(now, maxAgeMillis) }
        }.flowOn(computeDispatcher)

    /**
     * The freshest quote for every symbol the desk has sent one for.
     *
     * The per-symbol flow above is right for a screen about one company and wrong for the
     * feed, which would otherwise open a database subscription per visible ticker and
     * re-scan the same rows once for each. One pass, keyed by symbol, newest first.
     *
     * Staleness is enforced here for the same reason it is there: no screen should be
     * able to render a twenty-minute-old price as live, which is the exact mistake the
     * feature exists to prevent.
     */
    fun latestQuotes(
        maxAgeMillis: Long = DeskPayload.DEFAULT_STALE_MS,
    ): Flow<Map<String, DeskPayload>> =
        // Driven by a tick as well as the table. Staleness is a fact about elapsed time,
        // and a flow that only re-runs when a row is written cannot notice it: a desk
        // that stops sending leaves its last quote on screen, drawn as live, for as long
        // as the app stays open. That is the precise failure this staleness check was
        // added to prevent, and without the tick the check simply never runs again.
        combine(
            deskDao.observeRecent(QUOTE_SCAN_LIMIT),
            ticks(FRESHNESS_TICK_MS, clock),
        ) { rows, now ->
            val out = HashMap<String, DeskPayload>()
            // Rows arrive newest first, so the first quote seen for a symbol is the one
            // to keep — putIfAbsent rather than put.
            for (row in rows) {
                for (quote in row.toDomain().payloads) {
                    if (quote.ltp == null || quote.isStale(now, maxAgeMillis)) continue
                    out.putIfAbsent(quote.symbol, quote)
                }
            }
            out
        }.flowOn(computeDispatcher)

    fun unreadCount(): Flow<Int> = deskDao.observeUnreadCount()

    suspend fun markAllRead() = withContext(ioDispatcher) { deskDao.markAllRead() }

    /**
     * Sends a line to the desk.
     *
     * The bridge was one-way for no reason other than that nothing had needed the other
     * direction yet — meanwhile TickerForge has been announcing "Remote control online,
     * send /help" into a log the phone could only read. Publishing to the same topic is
     * the whole of what was missing.
     *
     * Deliberately untyped. The command vocabulary belongs to the desk and will change
     * without this app hearing about it, so anything sent is a line of text and anything
     * that comes back is a message like any other. A schema here would be a second place
     * to update every time a command is added.
     *
     * @return an error to show, or null when it went.
     */
    suspend fun send(text: String): String? = withContext(ioDispatcher) {
        val line = text.trim()
        if (line.isEmpty()) return@withContext null
        if (!preferences.isConfigured) return@withContext "The desk bridge is not set up yet"

        val result = try {
            fetcher.fetch(
                // The outbound topic, which is not always the one being polled: a desk
                // that subscribed and published to the same topic would hear itself.
                url = NtfyMessages.publishUrl(preferences.server, preferences.outboundTopic),
                validators = null,
                bearer = preferences.token.takeIf { it.isNotBlank() },
                body = line,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext e.message ?: "Could not reach the bridge"
        }

        when (result) {
            is FetchResult.Failure ->
                if (result.status > 0) "HTTP ${result.status}" else result.message
            // Nothing is stored locally on success. The message comes back on the next
            // poll with the server's own id and timestamp, so writing an optimistic copy
            // would mean reconciling two rows for one line the moment it arrives.
            else -> null
        }
    }

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
                // Which rows actually landed, not just how many. The poll overlaps its
                // watermark on purpose, so most batches re-deliver a message already
                // held — and announcing one of those would mean a signal arriving twice.
                val rowIds = if (messages.isEmpty()) {
                    emptyList()
                } else {
                    deskDao.insertAll(messages.map { it.toEntity() })
                }
                // A missing row id means the insert list and the result disagreed, which
                // should not happen — but treating the unknown case as "landed" is the
                // direction that announces a message twice, so it counts as already held.
                val stored = messages.filterIndexed { i, _ -> rowIds.getOrNull(i)?.let { it != -1L } == true }
                val inserted = stored.size
                deskDao.pruneOlderThan(clock() - KEEP_MS)
                Log.i(TAG, "desk: ${messages.size} fetched, $inserted new")
                // Newest wins. A batch can hold several — the desk sends one every few
                // minutes and a phone that has been asleep catches up on all of them —
                // and applying the older ones in turn would briefly restore positions
                // that were closed hours ago.
                val positions = messages
                    .sortedBy { it.receivedAtMillis }
                    .mapNotNull { NtfyJson.parsePositions(it.body) }
                    .lastOrNull()
                // Desk quotes are the better samples — real-time rather than delayed —
                // so they belong in the history alongside the exchange's.
                history?.record(messages.flatMap { NtfyJson.parseQuotes(it.body) })
                // Newest run per screen, for the same reason positions take the last
                // snapshot: a sleeping phone catches up on several and replaying the
                // older ones would briefly restore a list already superseded.
                val screens = messages
                    .sortedBy { it.receivedAtMillis }
                    .flatMap { NtfyJson.parseScreens(it.body) }
                    .associateBy { it.name }
                    .values
                    .toList()
                // Stored, not returned. See the constructor note on `candles`.
                stored.mapNotNull { NtfyJson.parseCandles(it.body) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { candles?.store(it) }
                DeskSyncResult(messages.size, inserted, null, stored, screens, positions)
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
    payloads = com.abhinavxt.newsforge.data.net.NtfyJson.parseQuotes(body),
    machine = com.abhinavxt.newsforge.data.net.NtfyJson.isMachinePayload(body),
)
