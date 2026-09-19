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
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * @return ranked matches, best first, or empty for a query too short to mean anything.
     *
     *   On the compute dispatcher because the first call parses the cached NSE list —
     *   a few thousand rows — and a reader typing into a search box is by definition
     *   watching the frame it would otherwise block.
     */
    suspend fun search(query: String, limit: Int = 6): List<SymbolEntry> =
        withContext(computeDispatcher) {
            SymbolSearch.search(SymbolLexiconProvider.entries(context), query, limit)
        }
}
