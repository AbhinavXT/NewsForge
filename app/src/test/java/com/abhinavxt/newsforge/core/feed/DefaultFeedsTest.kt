package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class DefaultFeedsTest {

    @Test
    fun feedIdsAreUnique() {
        // Ids are the foreign key stored articles hang off, so a collision would silently
        // merge two sources' health and history.
        val ids = DefaultFeeds.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyFeedUrlIsAbsoluteHttps() {
        for (feed in DefaultFeeds.ALL) {
            val uri = URI(feed.url)
            assertTrue("${feed.id} is not https", uri.scheme == "https")
            assertTrue("${feed.id} has no host", !uri.host.isNullOrEmpty())
        }
    }

    @Test
    fun googleNewsQueriesArePercentEncodedAndCarryTheIndiaEdition() {
        val url = DefaultFeeds.googleNews("block deal & bulk deal")
        assertTrue(url.startsWith("https://news.google.com/rss/search?q="))
        assertTrue("raw space leaked into the query", !url.contains(" "))
        assertTrue("raw ampersand leaked into the query", url.contains("%26"))
        assertTrue(url.contains("when%3A1d"))
        assertTrue(url.endsWith("&hl=en-IN&gl=IN&ceid=IN%3Aen"))
    }

    @Test
    fun queryFeedsAllCarryACategoryHint() {
        // The hint is the whole point of splitting by event type rather than publisher.
        for (feed in DefaultFeeds.QUERY_FEEDS) {
            assertTrue("${feed.id} has no category hint", feed.categoryHint != null)
        }
    }

    @Test
    fun everyBuiltInCanBeLookedUpForReset() {
        // The reset path depends on this: a built-in whose id is not resolvable here
        // would be uneditable-and-unrecoverable, the worst of both.
        for (feed in DefaultFeeds.ALL) {
            assertEquals(feed, DefaultFeeds.byId(feed.id))
        }
        assertTrue(DefaultFeeds.byId("not-a-feed") == null)
    }

    @Test
    fun everyShippedFeedIsOnByDefault() {
        // The NSE feeds shipped off while the session gate was unhandled. It is handled
        // now, and a feed that is off by default is a feed nobody ever turns on.
        assertTrue(DefaultFeeds.ALL.all { it.enabled })
        assertTrue(DefaultFeeds.ALL.size >= 15)
    }

    @Test
    fun onlyNseFeedsCarryANonRssKind() {
        for (feed in DefaultFeeds.QUERY_FEEDS + DefaultFeeds.PUBLISHER_FEEDS) {
            assertEquals("${feed.id} should be RSS", FeedKind.RSS, feed.kind)
        }
        assertTrue(DefaultFeeds.NSE_FEEDS.all { it.kind.isNse })
    }
}
