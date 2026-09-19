package com.abhinavxt.newsforge.data

import com.abhinavxt.newsforge.data.NewsRepository.Companion.escapeLike
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The search box is free text, and free text contains SQL wildcards.
 *
 * Untouched, `%` matches everything and `_` matches any single character, so a query
 * containing either returns rows that have nothing to do with it. The failure is quiet —
 * no crash, no empty list, just wrong results — which is why it is worth pinning.
 */
class SearchEscapingTest {

    @Test
    fun ordinaryTextIsUntouched() {
        assertEquals("Tata Steel", escapeLike("Tata Steel"))
        assertEquals("5,000 crore", escapeLike("5,000 crore"))
    }

    @Test
    fun percentIsEscaped() {
        assertEquals("""up 5\% today""", escapeLike("up 5% today"))
    }

    @Test
    fun underscoreIsEscaped() {
        assertEquals("""nifty\_50""", escapeLike("nifty_50"))
    }

    /**
     * The backslash has to be doubled before the wildcards are escaped, or the escape
     * characters this adds would themselves be escaped by a backslash the reader typed.
     */
    @Test
    fun backslashIsEscapedBeforeTheWildcards() {
        assertEquals("""a\\b""", escapeLike("""a\b"""))
        assertEquals("""a\\\%b""", escapeLike("""a\%b"""))
    }

    @Test
    fun aQueryOfNothingButWildcardsStillMeansItself() {
        assertEquals("""\%\%""", escapeLike("%%"))
    }
}
