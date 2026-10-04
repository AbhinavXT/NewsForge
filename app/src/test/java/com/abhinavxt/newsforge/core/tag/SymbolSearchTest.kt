package com.abhinavxt.newsforge.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking, which is the whole job here.
 *
 * Filtering is easy and nearly useless — the first row is the one that gets tapped, so a
 * correct set in the wrong order is a wrong answer.
 */
class SymbolSearchTest {

    private val entries = listOf(
        SymbolEntry("TCS", "Tata Consultancy Services", listOf("TCS")),
        SymbolEntry("TATAMOTORS", "Tata Motors"),
        SymbolEntry("TATAMTRDVR", "Tata Motors DVR"),
        SymbolEntry("TATASTEEL", "Tata Steel"),
        SymbolEntry("RELIANCE", "Reliance Industries", listOf("RIL", "Reliance")),
        SymbolEntry("HAL", "Hindustan Aeronautics"),
        SymbolEntry("BEL", "Bharat Electronics"),
    )

    private fun symbols(query: String) = SymbolSearch.search(entries, query).map { it.symbol }

    /** Somebody typing a ticker means that ticker and should not have to scroll. */
    @Test
    fun anExactTickerRanksFirst() {
        assertEquals("TCS", symbols("TCS").first())
        assertEquals("HAL", symbols("hal").first())
    }

    /**
     * "tata" is the ticker of three companies and a word in a fourth's name.
     *
     * The three whose ticker starts with it come first; Tata Consultancy, which only
     * matches on its name, comes last despite being the best-known of them.
     */
    @Test
    fun tickerPrefixesBeatNameMatches() {
        val results = symbols("tata")
        assertTrue(results.indexOf("TATASTEEL") < results.indexOf("TCS"))
        assertTrue(results.indexOf("TATAMOTORS") < results.indexOf("TCS"))
        assertEquals("TCS", results.last())
    }

    /** Between two names that both match, the plain one is almost always wanted. */
    @Test
    fun theShorterNameWinsATie() {
        val results = symbols("tata motors")
        assertEquals("TATAMOTORS", results.first())
        assertTrue(results.indexOf("TATAMOTORS") < results.indexOf("TATAMTRDVR"))
    }

    @Test
    fun aliasesAreSearchable() {
        assertTrue("RELIANCE" in symbols("ril"))
    }

    @Test
    fun nameSubstringsMatch() {
        assertTrue("HAL" in symbols("aeronautics"))
        assertTrue("BEL" in symbols("electronics"))
    }

    @Test
    fun searchIsCaseAndSpaceInsensitive() {
        assertEquals(symbols("tatasteel"), symbols("  TATASTEEL  "))
    }

    /**
     * Below the minimum the ranking is noise and the list is unreadable.
     *
     * Two characters would put a large part of the exchange behind a suggestion nobody
     * can scan, and the top row would still be arbitrary.
     */
    @Test
    fun aQueryTooShortMatchesNothing() {
        assertTrue(SymbolSearch.search(entries, "t").isEmpty())
        assertTrue(SymbolSearch.search(entries, "").isEmpty())
        assertTrue(SymbolSearch.search(entries, "   ").isEmpty())
    }

    @Test
    fun anUnknownQueryMatchesNothing() {
        assertTrue(SymbolSearch.search(entries, "zzzzz").isEmpty())
    }

    @Test
    fun theLimitIsHonoured() {
        assertEquals(2, SymbolSearch.search(entries, "tata", limit = 2).size)
    }

    /** Seeds are listed first, so the hand-written entry wins a duplicated symbol. */
    @Test
    fun aDuplicatedSymbolIsListedOnce() {
        val withDuplicate = entries + SymbolEntry("HAL", "HINDUSTAN AERONAUTICS LTD", generated = true)
        val results = SymbolSearch.search(withDuplicate, "hal")
        assertEquals(1, results.count { it.symbol == "HAL" })
        assertEquals("Hindustan Aeronautics", results.first { it.symbol == "HAL" }.name)
    }
}
