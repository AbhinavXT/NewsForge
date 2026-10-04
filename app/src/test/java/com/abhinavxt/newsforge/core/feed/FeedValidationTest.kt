package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedValidationTest {

    @Test
    fun acceptsAPlainFeedUrl() {
        assertNull(FeedValidation.validate("Mint Markets", "https://www.livemint.com/rss/markets"))
    }

    @Test
    fun rejectsMissingFields() {
        assertEquals(
            FeedProblem.NAME_EMPTY,
            FeedValidation.validate("  ", "https://example.com/rss"),
        )
        assertEquals(FeedProblem.URL_EMPTY, FeedValidation.validate("Mint", "   "))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertEquals(
            FeedProblem.URL_NOT_HTTP,
            FeedValidation.validate("X", "ftp://example.com/rss"),
        )
        assertEquals(
            FeedProblem.URL_NOT_HTTP,
            FeedValidation.validate("X", "example.com/rss"),
        )
    }

    @Test
    fun rejectsUrlsWithNoHost() {
        assertEquals(FeedProblem.URL_NO_HOST, FeedValidation.validate("X", "https:///rss"))
    }

    @Test
    fun rejectsADuplicateEvenWhenSpeltDifferently() {
        val existing = setOf("https://example.com/rss")
        assertEquals(
            FeedProblem.DUPLICATE_URL,
            FeedValidation.validate("X", "HTTPS://Example.com/rss/", existing),
        )
    }

    @Test
    fun normalisationLowercasesSchemeAndHostAndDropsTrailingSlash() {
        assertEquals(
            "https://example.com/rss",
            FeedValidation.normalizeUrl("HTTPS://Example.COM/rss/"),
        )
    }

    @Test
    fun normalisationKeepsTheQueryString() {
        // A Google News search feed *is* its query string; stripping it the way the
        // article canonicaliser does would turn every query feed into the same URL.
        val url = "https://news.google.com/rss/search?q=block+deal&hl=en-IN&gl=IN&ceid=IN%3Aen"
        assertEquals(url, FeedValidation.normalizeUrl(url))
        assertNull(FeedValidation.validate("Block deals", url))
    }

    @Test
    fun normalisationLeavesUnparseableInputAlone() {
        assertEquals("not a url", FeedValidation.normalizeUrl("not a url"))
    }

    @Test
    fun idIsHostDerivedStableAndDistinct() {
        val a = FeedValidation.idFor("https://www.moneycontrol.com/rss/marketreports.xml")
        val b = FeedValidation.idFor("https://www.moneycontrol.com/rss/business.xml")
        assertTrue("id was $a", a.startsWith("moneycontrol-com-"))
        // Two feeds from one site must not collide.
        assertNotEquals(a, b)
        assertEquals(
            a,
            FeedValidation.idFor("HTTPS://WWW.Moneycontrol.com/rss/marketreports.xml/"),
        )
    }

    @Test
    fun wwwIsPreservedBecauseTheUrlHasToActuallyFetch() {
        // Unlike an article link, which we only ever compare, a feed URL gets requested.
        // Some hosts answer on www and 404 without it, so the two stay distinct.
        assertNotEquals(
            FeedValidation.normalizeUrl("https://www.example.com/rss"),
            FeedValidation.normalizeUrl("https://example.com/rss"),
        )
    }

    @Test
    fun idSurvivesAHostThatIsAllPunctuation() {
        assertTrue(FeedValidation.idFor("https://1.2.3.4/rss").isNotEmpty())
        assertTrue(FeedValidation.idFor("not a url").isNotEmpty())
    }

    @Test
    fun everySeededFeedPassesValidation() {
        // The seeded set goes through the same door as anything typed; a malformed
        // default would otherwise only show up as a feed that never fetches.
        for (feed in DefaultFeeds.ALL) {
            assertNull("${feed.id} is invalid", FeedValidation.validate(feed.name, feed.url))
        }
    }

    @Test
    fun seededFeedUrlsAreAllDistinctAfterNormalisation() {
        val normalized = DefaultFeeds.ALL.map { FeedValidation.normalizeUrl(it.url) }
        assertEquals(normalized.size, normalized.toSet().size)
    }
}
