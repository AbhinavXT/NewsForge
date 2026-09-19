package com.abhinavxt.newsforge.data.tag

import android.content.Context
import android.util.Log
import com.abhinavxt.newsforge.core.tag.InstrumentCsv
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.core.tag.SymbolTable
import java.io.File
import java.io.IOException

/**
 * Builds the tagging lexicon from the best source available.
 *
 * Three, in order of preference: the NSE company list fetched by
 * [com.abhinavxt.newsforge.data.tag.InstrumentRepository], a bundled `symbols.tsv`, and
 * the hand-written [SeedSymbols]. Each is a strictly wider net than the one below it, and
 * all three fall back cleanly, so a phone that has never reached NSE still tags.
 *
 * Fetching beats bundling because the exchange keeps listing companies after the release
 * is cut, and the failure mode of a stale lexicon is invisible: a story about a company
 * nobody taught the app about produces an article with no symbols, which no filter
 * surfaces and no alert fires for. Nothing in the app can report the story it did not tag.
 */
object SymbolLexiconProvider {

    const val ASSET_NAME = "symbols.tsv"

    @Volatile
    private var cached: SymbolLexicon? = null

    @Volatile
    private var cachedEntries: List<SymbolEntry>? = null

    /**
     * Built once and reused: assembling the phrase table for a few thousand entries is
     * not expensive, but it is pointless to repeat on every sync.
     */
    fun lexicon(context: Context): SymbolLexicon =
        cached ?: synchronized(this) {
            cached ?: build(context).also { cached = it }
        }

    /**
     * The same companies, as a plain list.
     *
     * The lexicon is a phrase table built for matching headlines and cannot be walked or
     * ranked, so search needs the entries themselves. Built from exactly the same three
     * sources in the same order, because a company the app can tag but cannot find would
     * be a strange thing to explain.
     */
    fun entries(context: Context): List<SymbolEntry> =
        cachedEntries ?: synchronized(this) {
            cachedEntries ?: buildEntries(context).also { cachedEntries = it }
        }

    /** Dropped after a refresh, so the next tagging pass sees the new companies. */
    fun invalidate() {
        synchronized(this) {
            cached = null
            cachedEntries = null
        }
    }

    private fun buildEntries(context: Context): List<SymbolEntry> {
        val fetched = readInstrumentCache(context)
        if (fetched.isNotEmpty()) return SeedSymbols.ENTRIES + fetched
        val asset = readAsset(context)
        return if (asset.isEmpty()) SeedSymbols.ENTRIES else SeedSymbols.ENTRIES + asset
    }

    private fun build(context: Context): SymbolLexicon {
        val fetched = readInstrumentCache(context)
        if (fetched.isNotEmpty()) {
            Log.i(TAG, "Tagging with ${fetched.size} symbols from the NSE company list")
            // Seeds first so their hand-written aliases, sectors and single-token claims
            // win every collision with a bulk row for the same company.
            return SymbolLexicon(SeedSymbols.ENTRIES + fetched)
        }

        val entries = readAsset(context)
        return if (entries.isEmpty()) {
            Log.i(TAG, "No company list; tagging with the ${SeedSymbols.ENTRIES.size}-name seed list")
            SeedSymbols.LEXICON
        } else {
            Log.i(TAG, "Tagging with ${entries.size} symbols from $ASSET_NAME")
            SymbolLexicon(SeedSymbols.ENTRIES + entries)
        }
    }

    private fun readInstrumentCache(context: Context) = try {
        File(context.filesDir, INSTRUMENT_FILE_NAME)
            .takeIf { it.isFile }
            ?.let { InstrumentCsv.parse(it.readText()) }
            .orEmpty()
    } catch (e: IOException) {
        emptyList()
    }

    private fun readAsset(context: Context) = try {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            SymbolTable.parse(reader.readText())
        }
    } catch (e: IOException) {
        // A missing asset is the expected case, not an error.
        emptyList()
    }

    /** Written by [com.abhinavxt.newsforge.data.tag.InstrumentRepository]. */
    const val INSTRUMENT_FILE_NAME = "nse-equity-list.csv"

    private const val TAG = "SymbolLexicon"
}
