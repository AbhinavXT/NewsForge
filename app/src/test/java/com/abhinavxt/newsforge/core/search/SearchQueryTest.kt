package com.abhinavxt.newsforge.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {

    @Test
    fun everyWordIsAPrefixAndAllMustMatch() {
        assertEquals("ireda* order*", SearchQuery.toMatch("IREDA order"))
    }

    @Test
    fun ftsSyntaxInTheInputIsNeutralised() {
        // A quote, a leading minus, a star or a parenthesis all mean something to FTS,
        // and an unbalanced quote is a SQL error. Reduced to words, they mean nothing.
        assertEquals("rate* cut*", SearchQuery.toMatch("\"rate cut"))
        assertEquals("bajaj* auto*", SearchQuery.toMatch("Bajaj-Auto"))
        assertEquals("tata* steel*", SearchQuery.toMatch("(tata* steel)"))
    }

    @Test
    fun operatorsBecomePlainWords() {
        // FTS only treats these as operators in upper case, and everything is lower-cased,
        // so "rate or cut" asks for all three words rather than for either side.
        assertEquals("rate* or* cut*", SearchQuery.toMatch("rate OR cut"))
        assertEquals("rbi* near* repo*", SearchQuery.toMatch("RBI NEAR repo"))
    }

    @Test
    fun singleLettersAreDroppedButShortTermsSurvive() {
        // "a*" would match most of the index. Two letters still admits these.
        assertEquals("5g* ai* it*", SearchQuery.toMatch("5G AI IT a"))
    }

    @Test
    fun inputWithNoSearchableWordsIsNull() {
        // Left for the ticker-only query, which is what "L&T" and "M&M" actually are.
        assertNull(SearchQuery.toMatch("L&T"))
        assertNull(SearchQuery.toMatch("M&M"))
        assertNull(SearchQuery.toMatch("  %  "))
        assertNull(SearchQuery.toMatch(""))
    }

    @Test
    fun repeatsAreDroppedAndLengthIsCapped() {
        assertEquals("tata* steel*", SearchQuery.toMatch("tata steel tata steel"))
        val long = (1..20).joinToString(" ") { "word$it" }
        assertEquals(SearchQuery.MAX_TERMS, SearchQuery.terms(long).size)
    }

    @Test
    fun indianScriptsAreWordsToo() {
        // Letters in any script, not just ASCII: a Hindi headline is searchable.
        assertEquals(listOf("रिलायंस"), SearchQuery.terms("रिलायंस"))
    }

    @Test
    fun aGroupedNumberBecomesAnExactPhrase() {
        // The index splits "1,200" at the comma, so the token "1200" does not exist.
        // Read as words, "1" is too short to keep and "200" alone would also match
        // "2,200 crore" and "200 crore". The phrase matches only what was written.
        assertEquals("\"1 200\" crore*", SearchQuery.toMatch("1,200 crore"))
        assertEquals("\"1 00 000\"", SearchQuery.toMatch("1,00,000"))
        assertEquals("rs* 500* crore*", SearchQuery.toMatch("Rs 500 crore"))
    }

    @Test
    fun foldingMatchesTheIndexExactly() {
        // SQLite's tokenizer lower-cases A-Z and nothing else. Folding "É" as well would
        // produce a term the index does not contain.
        assertEquals(listOf("sociÉtÉ"), SearchQuery.terms("SOCIÉTÉ"))
        assertEquals(listOf("société"), SearchQuery.terms("Société"))
    }
}
