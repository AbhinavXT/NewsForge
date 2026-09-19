@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.PriceSample
import com.abhinavxt.newsforge.data.db.PriceSampleDao
import com.abhinavxt.newsforge.data.db.PriceSampleEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the app has seen prices do.
 *
 * Exists for one question: what was this worth when the story broke. Neither the desk nor
 * the exchange endpoint will answer that about 10:42 once it is 11:15 — a quote is always
 * about now — so the only way to have the number is to have been watching and to have
 * written it down.
 *
 * Which the app already was. Quotes arrive every minute through the session whether or not
 * anyone looks at them; this simply stops throwing them away.
 */
class PriceHistoryRepository(
    private val dao: PriceSampleDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Samples recent enough to measure a story against, grouped by symbol.
     *
     * Bounded by [KEEP_MS] rather than by the news window: a reaction is only ever
     * computed against today or yesterday, and holding a week of ticks to serve that
     * would be paying storage for rows nothing can ask about.
     */
    fun recent(): Flow<Map<String, List<PriceSample>>> =
        // Rebuilt per tick for the same reason the feed's window is: the feed subscribes
        // to this once and holds it, so a bound taken at construction would keep serving
        // yesterday's two days a week later.
        ticks(WINDOW_TICK_MS, clock)
            .flatMapLatest { now -> dao.observeSince(now - KEEP_MS) }
            .map { rows ->
                rows.groupBy({ it.symbol }) { PriceSample(it.symbol, it.atMillis, it.price) }
            }
            .flowOn(computeDispatcher)

    /**
     * Writes down what the quotes say, at the moment they say it.
     *
     * Stamped with the quote's own timestamp rather than the clock. A price NSE published
     * for 11:40 is a fact about 11:40 even if it reached the phone at 11:44, and recording
     * it as the later time would quietly shift every reaction measured against it.
     */
    suspend fun record(quotes: Collection<DeskPayload>) {
        if (quotes.isEmpty()) return
        withContext(ioDispatcher) {
            val now = clock()
            val rows = quotes.mapNotNull { quote ->
                val price = quote.ltp?.takeIf { it > 0.0 } ?: return@mapNotNull null
                val at = quote.timestampMillis ?: now
                // A stamp from the future is a sender with a wrong clock, and a sample
                // ahead of "now" would attach itself to stories published after it.
                if (at > now + SKEW_TOLERANCE_MS) return@mapNotNull null
                PriceSampleEntity(quote.symbol, at, price)
            }
            if (rows.isEmpty()) return@withContext
            dao.insertAll(rows)
        }
    }

    /** Called from the sync path, so the table cannot grow without a poll happening. */
    suspend fun prune() = withContext(ioDispatcher) {
        val removed = dao.pruneOlderThan(clock() - KEEP_MS)
        if (removed > 0) Log.i(TAG, "pruned $removed price samples")
    }

    private companion object {
        const val TAG = "PriceHistory"

        /**
         * Two days: enough to measure yesterday evening's story against this morning,
         * and not enough to accumulate.
         */
        const val KEEP_MS = 2L * 24 * 60 * 60 * 1000

        const val SKEW_TOLERANCE_MS = 5L * 60 * 1000
    }
}
