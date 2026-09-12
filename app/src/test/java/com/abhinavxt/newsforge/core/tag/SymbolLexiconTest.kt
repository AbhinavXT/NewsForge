package com.abhinavxt.newsforge.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolLexiconTest {

    private val lexicon = SeedSymbols.LEXICON

    @Test
    fun matchesFullCompanyName() {
        assertEquals(listOf("RELIANCE"), lexicon.match("Reliance Industries Q2 net profit rises"))
    }

    @Test
    fun matchesShortFormAlias() {
        assertEquals(listOf("RELIANCE"), lexicon.match("RIL bags Rs 5,000 crore order"))
        assertEquals(listOf("LT"), lexicon.match("L&T wins metro contract"))
    }

    @Test
    fun toleratesCorporateSuffixes() {
        assertEquals(listOf("INFY"), lexicon.match("Infosys Ltd raises guidance"))
        assertEquals(listOf("INFY"), lexicon.match("Infosys Limited raises guidance"))
    }

    @Test
    fun longestMatchWinsBetweenSiblingCompanies() {
        assertEquals(listOf("BAJFINANCE"), lexicon.match("Bajaj Finance NIM compresses"))
        assertEquals(listOf("BAJAJFINSV"), lexicon.match("Bajaj Finserv board meets"))
        // "SBI Life" must not resolve to the bank just because "SBI" appears first.
        assertEquals(listOf("SBILIFE"), lexicon.match("SBI Life Insurance premium income up"))
        assertEquals(listOf("SBIN"), lexicon.match("State Bank of India cuts MCLR"))
    }

    @Test
    fun matchesSeveralCompaniesInOrderOfAppearance() {
        assertEquals(
            listOf("TATAMOTORS", "TATASTEEL"),
            lexicon.match("Tata Motors and Tata Steel lead the Nifty higher"),
        )
    }

    @Test
    fun deduplicatesRepeatedMentions() {
        assertEquals(
            listOf("INFY"),
            lexicon.match("Infosys guidance: Infosys said Infosys will grow"),
        )
    }

    @Test
    fun ignoresExchangeAndRegulatorAbbreviations() {
        // Tagging every market headline with the listed exchange entity would be useless.
        assertTrue(lexicon.match("SEBI tightens disclosure norms for BSE and NSE").isEmpty())
        assertTrue(lexicon.match("RBI holds repo rate").isEmpty())
    }

    @Test
    fun ignoresBareSectorWords() {
        assertTrue(lexicon.match("Steel stocks rally as metal prices firm up").isEmpty())
        assertTrue(lexicon.match("Tata group companies gain").isEmpty())
    }

    @Test
    fun handlesEmptyAndSymbolOnlyInput() {
        assertTrue(lexicon.match("").isEmpty())
        assertTrue(lexicon.match("!!! ??? ---").isEmpty())
    }

    @Test
    fun customLexiconRespectsFirstWriterForSharedAlias() {
        val custom = SymbolLexicon(
            listOf(
                SymbolEntry("BIGCO", "Big Company", listOf("Shared Alias")),
                SymbolEntry("SMALLCO", "Small Company", listOf("Shared Alias")),
            )
        )
        assertEquals(listOf("BIGCO"), custom.match("Shared Alias posts results"))
        assertFalse(custom.match("Small Company posts results").contains("BIGCO"))
    }
}
