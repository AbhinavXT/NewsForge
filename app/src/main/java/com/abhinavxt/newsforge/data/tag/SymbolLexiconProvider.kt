package com.abhinavxt.newsforge.data.tag

import android.content.Context
import android.util.Log
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.core.tag.SymbolTable
import java.io.IOException

/**
 * Builds the tagging lexicon, preferring a bundled `symbols.tsv` asset over the seed list.
 *
 * The asset is optional on purpose. Without it the app still tags the hundred names in
 * [SeedSymbols]; with it, the whole NSE universe. That means the repo stays free of a
 * two-thousand-line generated source file, and refreshing the universe is a script run
 * rather than a code change — see `tools/generate_symbols.py`.
 */
object SymbolLexiconProvider {

    const val ASSET_NAME = "symbols.tsv"

    @Volatile
    private var cached: SymbolLexicon? = null

    /**
     * Built once and reused: assembling the phrase table for a few thousand entries is
     * not expensive, but it is pointless to repeat on every sync.
     */
    fun lexicon(context: Context): SymbolLexicon =
        cached ?: synchronized(this) {
            cached ?: build(context).also { cached = it }
        }

    private fun build(context: Context): SymbolLexicon {
        val entries = readAsset(context)
        return if (entries.isEmpty()) {
            Log.i(TAG, "No $ASSET_NAME asset; tagging with the ${SeedSymbols.ENTRIES.size}-name seed list")
            SeedSymbols.LEXICON
        } else {
            Log.i(TAG, "Tagging with ${entries.size} symbols from $ASSET_NAME")
            // Seed entries go in first so their hand-written aliases win any collision
            // with a generated row of the same name.
            SymbolLexicon(SeedSymbols.ENTRIES + entries)
        }
    }

    private fun readAsset(context: Context) = try {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            SymbolTable.parse(reader.readText())
        }
    } catch (e: IOException) {
        // A missing asset is the expected case, not an error.
        emptyList()
    }

    private const val TAG = "SymbolLexicon"
}
