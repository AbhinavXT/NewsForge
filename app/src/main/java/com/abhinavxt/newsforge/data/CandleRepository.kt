@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.Candles
import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.core.quote.CandleBatch
import com.abhinavxt.newsforge.core.quote.CandleInterval
import com.abhinavxt.newsforge.data.db.CandleDao
import com.abhinavxt.newsforge.data.db.CandleEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Price history for one company at a time.
 *
 * The app holds no broker credentials and should not — see
 * [com.abhinavxt.newsforge.core.quote.ExchangeQuotes] for the same reasoning about
 * quotes. Kite lives on the desk, so this asks rather than fetches: it publishes a line
 * to the bridge, and the answer arrives on an ordinary poll like everything else the desk
 * sends.
 *
 * That makes the round trip slow and unreliable in a way an HTTP call is not — the desk
 * may be asleep, and the reply lands whenever the next poll runs. Which is the whole
 * reason the bars are cached: a company opened a second time draws immediately from what
 * is already stored, and the request that goes out alongside is a top-up rather than the
 * thing being waited on.
 *
 * Deliberately knows nothing about [DeskRepository]. It is handed a function that sends a
 * line, so the separation the two stores already keep — the desk's messages never reach
 * the article store, the article store never reaches the bridge — holds here too.
 */
class CandleRepository(
    private val dao: CandleDao,
    /**
     * Publishes a line to the desk and returns an error to show, or null when it went.
     *
     * Wired to `DeskRepository::send`. A function rather than the repository so this can
     * be exercised without a bridge, and so nothing here can reach the rest of the desk.
     */
    private val publish: suspend (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * The stored bars for one company and size, oldest first.
     *
     * Bounded by [CandleInterval] rather than by a single window: a year of daily bars is
     * the point of the daily chart, and a year of one-minute bars is several hundred
     * thousand rows nobody will scroll.
     */
    fun series(symbol: String, interval: CandleInterval): Flow<List<Candle>> {
        val wanted = symbol.trim().uppercase()
        return ticks(WINDOW_TICK_MS, clock)
            .flatMapLatest { now ->
                dao.observeSeries(
                    symbol = wanted,
                    interval = interval.wireName,
                    since = now - keepMillis(interval),
                )
            }
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Stores a batch the desk sent.
     *
     * Upsert, not insert: the newest bar is still forming while the session runs, and the
     * desk re-sends it each time it is asked. Every arrival of it is a better reading of
     * the same interval.
     */
    suspend fun store(batches: List<CandleBatch>) = withContext(ioDispatcher) {
        if (batches.isEmpty()) return@withContext
        for (batch in batches) {
            dao.upsertAll(
                batch.candles.map { candle ->
                    CandleEntity(
                        symbol = batch.symbol,
                        interval = batch.interval.wireName,
                        openTimeMillis = candle.openTimeMillis,
                        open = candle.open,
                        high = candle.high,
                        low = candle.low,
                        close = candle.close,
                        volume = candle.volume,
                    )
                }
            )
        }
        // Pruned on the write rather than on a timer, so the table cannot grow without
        // something having arrived to grow it.
        for (interval in CandleInterval.entries) {
            dao.pruneOlderThan(interval.wireName, clock() - keepMillis(interval))
        }
        Log.i(TAG, "candles: ${batches.joinToString { "${it.symbol}/${it.interval.wireName}=${it.candles.size}" }}")
    }

    /**
     * Asks the desk for a history, unless one recent enough is already held.
     *
     * The gap check is on the newest stored bar rather than on when the last request was
     * made. Asking again for bars already on the phone is the cost this avoids, and the
     * desk may have ignored the previous request entirely — in which case a
     * request-time check would wait out the gap having received nothing.
     *
     * @return true when a line was actually published — so a caller can wait for a reply
     *   it has reason to expect, and not poll the bridge for one it never asked for.
     */
    suspend fun request(
        symbol: String,
        interval: CandleInterval,
        bars: Int = defaultBars(interval),
    ): Boolean = withContext(ioDispatcher) {
        val wanted = symbol.trim().uppercase()
        val newest = dao.newestOpenTime(wanted, interval.wireName)
        val held = dao.count(wanted, interval.wireName)
        val fresh = newest != null &&
            clock() - newest < staleAfterMillis(interval) &&
            held >= bars / 2
        if (fresh) return@withContext false
        val failure = publish(Candles.request(wanted, interval, bars))
        if (failure != null) Log.w(TAG, "candle request for $wanted: $failure")
        failure == null
    }

    /**
     * How much history is worth keeping, by bar size.
     *
     * Two years of daily bars so the 52-week range has a full window either side of
     * today, and three sessions of intraday so yesterday's shape is still there to
     * compare this morning against. Intraday bars are the ones that accumulate — a single
     * name at one-minute resolution is 375 rows a day — so they get the short lease.
     */
    private fun keepMillis(interval: CandleInterval): Long =
        if (interval.isIntraday) INTRADAY_KEEP_MS else DAILY_KEEP_MS

    /**
     * How stale the newest bar may be before a top-up is worth asking for.
     *
     * A daily series is complete until the next close, so re-asking during the day buys
     * nothing but a message. An intraday one is out of date within minutes by
     * construction.
     */
    private fun staleAfterMillis(interval: CandleInterval): Long =
        if (interval.isIntraday) INTRADAY_STALE_MS else DAILY_STALE_MS

    private companion object {
        const val TAG = "CandleRepository"

        const val DAILY_KEEP_MS = 2L * 365 * 24 * 60 * 60 * 1000
        const val INTRADAY_KEEP_MS = 3L * 24 * 60 * 60 * 1000

        const val DAILY_STALE_MS = 12L * 60 * 60 * 1000
        const val INTRADAY_STALE_MS = 5L * 60 * 1000

        /**
         * Bars asked for when the caller has no opinion.
         *
         * Daily is sized to cover a 52-week window with room to spare — 300 trading days
         * is about fourteen months. Intraday is one session plus the tail of the previous
         * one, which is as much as a phone chart can usefully show at that resolution.
         */
        fun defaultBars(interval: CandleInterval): Int =
            if (interval.isIntraday) 400 else 300
    }
}

private fun CandleEntity.toDomain() = Candle(
    openTimeMillis = openTimeMillis,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
)
