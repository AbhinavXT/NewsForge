package com.abhinavxt.newsforge.data.tag

import android.content.Context
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.core.tag.SymbolSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every listed company, searchable by ticker or name.
 *
 * Exists so the app can reach a company it has no news about. Until now the only route to
 * a research screen was tapping a ticker on a story, which meant the chart — the part that
 * does not depend on coverage at all — was unreachable for anything quiet. A reader
 * wanting to look at a stock had to wait for it to be in the headlines.
 *
 * Reads the same list the tagger does, so the set of companies the app can find and the
 * set it can recognise in a headline are the same set.
 */
class SymbolDirectory(
    private val context: Context,
    /**
     * IO, not Default, and the distinction matters more than it looks.
     *
     * The first search reads the cached NSE list off disk and parses a few thousand rows
     * inside a lock. Done on Default, that occupies one of a small pool of threads that
     * the feed's own state is assembled on — so the first keystroke in the search box
     * could stall the recomposition that was supposed to render it, and the box appeared
     * not to accept typing. It is file work with a parse attached; it belongs on IO.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * @return ranked matches, best first, or empty for a query too short to mean anything.
     *
     *   Off the main thread because the first call parses the cached NSE list — a few
     *   thousand rows — and a reader typing into a search box is by definition watching
     *   the frame it would otherwise block.
     */
    suspend fun search(query: String, limit: Int = 6): List<SymbolEntry> =
        withContext(ioDispatcher) {
            SymbolSearch.search(SymbolLexiconProvider.entries(context), query, limit)
        }
}
