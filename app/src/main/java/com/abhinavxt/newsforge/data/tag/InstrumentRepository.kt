package com.abhinavxt.newsforge.data.tag

import android.content.Context
import android.util.Log
import com.abhinavxt.newsforge.core.tag.InstrumentCsv
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a refresh did, so the screen can say something specific. */
data class InstrumentRefresh(val symbols: Int, val error: String? = null)

/**
 * Keeps the list of listed companies current.
 *
 * Cached as the raw CSV in app storage rather than in a table. It is read whole, once, at
 * lexicon build time and never queried by symbol — so a table would buy indexing nothing
 * uses, and cost a migration and a schema to hold what is already a file.
 *
 * Refreshed weekly. The exchange lists a handful of companies a month; asking daily would
 * spend a request to learn nothing, and asking never is how the lexicon goes stale without
 * anybody noticing.
 */
class InstrumentRepository(
    private val context: Context,
    private val fetcher: FeedFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** Cached text, or null when nothing has been fetched yet. */
    suspend fun cached(): String? = withContext(ioDispatcher) {
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    suspend fun lastRefreshedAt(): Long? = withContext(ioDispatcher) {
        file.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }
    }

    suspend fun isStale(): Boolean = (lastRefreshedAt() ?: 0L) < clock() - REFRESH_INTERVAL_MS

    /**
     * @param force ignores the weekly interval, for the button on the feeds screen. A
     *   person pressing Refresh has a reason and should not be told to come back Tuesday.
     */
    suspend fun refresh(force: Boolean = false): InstrumentRefresh? = withContext(ioDispatcher) {
        if (!force && !isStale()) return@withContext null

        val result = try {
            fetcher.fetch(
                url = InstrumentCsv.URL,
                validators = null,
                // The archives host gates on a browser session exactly like the API does.
                referer = FeedFetcher.NSE_HOME,
                prime = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "instrument fetch failed", e)
            return@withContext InstrumentRefresh(0, e.message ?: "Could not reach NSE")
        }

        if (result !is FetchResult.Success) {
            return@withContext InstrumentRefresh(0, "NSE did not return the company list")
        }

        val text = result.bytes.toString(Charsets.UTF_8)
        val parsed = InstrumentCsv.parse(text)
        // A parse that yields almost nothing means the format moved or a block page came
        // back with a 200. Keeping the previous cache beats replacing a working lexicon
        // with an empty one, which would silently stop tagging everything.
        if (parsed.size < MIN_PLAUSIBLE_SYMBOLS) {
            Log.w(TAG, "instrument list looked wrong: ${parsed.size} symbols, keeping cache")
            return@withContext InstrumentRefresh(0, "That did not look like the company list")
        }

        runCatching { file.writeText(text) }.onFailure {
            Log.w(TAG, "could not cache instrument list", it)
        }
        SymbolLexiconProvider.invalidate()
        Log.i(TAG, "instruments: ${parsed.size} symbols")
        InstrumentRefresh(parsed.size)
    }

    private companion object {
        const val TAG = "Instruments"
        val FILE_NAME = SymbolLexiconProvider.INSTRUMENT_FILE_NAME
        const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Floor for believing a response. NSE lists a couple of thousand companies; a few
         * dozen means something other than the list came back.
         */
        const val MIN_PLAUSIBLE_SYMBOLS = 500
    }
}
