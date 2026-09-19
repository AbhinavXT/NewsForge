package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.ExchangeQuotes
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.quote.MarketBreadths
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.NseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Prices for the names in the news, from the exchange.
 *
 * The baseline under the desk bridge: it needs nothing running at home, and it is the
 * exchange publishing its own data rather than anything scraped. One request returns a
 * whole index; only the symbols the feed has actually tagged are kept.
 *
 * Held in memory rather than a table, which looks like a shortcut and is not. A quote is
 * worth showing for twenty-odd minutes and the app is cold-started far less often than
 * that — so a persisted one would be stale in every case where persistence would have
 * helped, and the schema, the migration and the pruning would all exist to serve a row
 * that is discarded on read.
 *
 * Failures are silent by design. No price is an ordinary state here — the endpoint is
 * unofficial, session-gated and occasionally blocks a phone outright — and an error
 * banner over the news because a nice-to-have did not load would be the wrong trade.
 */
class QuoteRepository(
    private val fetcher: FeedFetcher,
    /** Every fetched price is written down, so stories can later be measured against it. */
    private val history: PriceHistoryRepository? = null,
    /** Accumulates each session's volume shape, so "unusually busy" can mean something. */
    private val volume: VolumeHistoryRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val index: String = ExchangeQuotes.DEFAULT_INDEX,
) {

    private val state = MutableStateFlow<Map<String, DeskPayload>>(emptyMap())
    private val breadthState = MutableStateFlow<MarketBreadth?>(null)

    /** Latest exchange quotes, by symbol. Empty until the first successful fetch. */
    fun quotes(): StateFlow<Map<String, DeskPayload>> = state.asStateFlow()

    /**
     * What the whole index did, alongside the handful of quotes that were kept.
     *
     * Free: the rows are already parsed and already in memory when the narrowing happens,
     * so this costs a fold over a list that was about to be discarded. Nullable because a
     * response too short to describe a market should read as "no context" rather than as
     * a flat one.
     */
    fun breadth(): StateFlow<MarketBreadth?> = breadthState.asStateFlow()

    /** Serialises fetches, so three callers arriving together make one request. */
    private val lock = Mutex()

    private var lastFetchedAt = 0L

    /**
     * @param wanted symbols the feed has tagged. Empty means "keep nothing" rather than
     *   "keep everything" — before the first article is stored there is no reason to hold
     *   five hundred quotes, and an empty feed should cost an empty map.
     * @param minGapMillis skip when a fetch finished less than this ago. The live watch
     *   and the open feed both call this on their own timers and would otherwise ask the
     *   exchange twice for a response neither of them could have used differently.
     */
    suspend fun refresh(wanted: Set<String>, minGapMillis: Long = MIN_GAP_MS) {
        if (wanted.isEmpty()) {
            state.value = emptyMap()
            return
        }
        lock.withLock {
            if (clock() - lastFetchedAt < minGapMillis) return
            withContext(ioDispatcher) {
                val result = try {
                    fetcher.fetch(
                        url = ExchangeQuotes.urlFor(index),
                        validators = null,
                        // The endpoint refuses anything without a browser session, the
                        // same way the filing feeds do, and priming is already handled.
                        referer = FeedFetcher.NSE_HOME,
                        prime = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "quote fetch failed", e)
                    return@withContext
                }

                if (result !is FetchResult.Success) {
                    Log.w(TAG, "quotes: $result")
                    return@withContext
                }

                val rows = try {
                    NseJson.rows(result.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "quote parse failed", e)
                    return@withContext
                }
                val quotes = ExchangeQuotes.fromRows(rows, wanted, clock())
                // Taken from every row, before the narrowing. The four hundred and eighty
                // constituents nobody has tagged are what makes the twenty that were
                // tagged mean anything.
                val marketBreadth = MarketBreadths.fromRows(rows, index, clock())

                lastFetchedAt = clock()
                // Replaced rather than merged. A symbol that has dropped out of the index
                // or out of the news should stop having a price, not keep the last one it
                // had — which would go on ageing invisibly behind a fresh-looking map.
                state.value = quotes.associateBy { it.symbol }
                // Left alone on a response too short to measure, rather than blanked: a
                // momentary bad response should not make the market context flicker away
                // and back on a screen somebody is reading.
                marketBreadth?.let { breadthState.value = it }
                history?.record(quotes)
                history?.prune()
                volume?.record(quotes)
                Log.i(TAG, "quotes: ${quotes.size} of ${wanted.size} wanted")
            }
        }
    }

    private companion object {
        const val TAG = "QuoteRepository"

        /**
         * Exchange data is delayed before it is published, so asking more often than this
         * returns the same numbers and spends the request anyway.
         */
        const val MIN_GAP_MS = 60_000L
    }
}
