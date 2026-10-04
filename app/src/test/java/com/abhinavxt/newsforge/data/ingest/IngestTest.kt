package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.FeedSource
import com.abhinavxt.newsforge.core.model.ParsedItem
import com.abhinavxt.newsforge.core.model.SourceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestTest {

    private val now = 1_757_400_000_000L
    private val minute = 60_000L

    private val feed = FeedSource(
        id = "gn-orders",
        name = "Order wins",
        url = "https://news.google.com/rss/search?q=x",
        tier = SourceTier.AGGREGATOR,
        categoryHint = Category.ORDER_WIN,
    )

    private fun item(
        title: String = "Tata Steel bags Rs 2,000 crore order from Indian Railways",
        link: String = "https://www.example.com/story/1?utm_source=rss",
        published: Long? = now - 5 * minute,
        summary: String? = null,
        sourceName: String? = null,
    ) = ParsedItem(title, link, summary, published, guid = null, sourceName = sourceName)

    @Test
    fun mapsTheStraightforwardFields() {
        val article = Ingest.toArticle(item(), feed, now)
        assertEquals("gn-orders", article.feedId)
        assertEquals(SourceTier.AGGREGATOR, article.tier)
        assertEquals(Category.ORDER_WIN, article.category)
        assertEquals(now - 5 * minute, article.publishedAt)
        assertEquals(now, article.fetchedAt)
        assertTrue(article.hadPublishedDate)
    }

    @Test
    fun idIsDerivedFromTheCanonicalUrlNotTheRawLink() {
        val a = Ingest.toArticle(item(link = "https://www.example.com/story/1?utm_source=rss"), feed, now)
        val b = Ingest.toArticle(item(link = "http://example.com/story/1/#top"), feed, now)
        // Same article behind different tracking must collapse to one row.
        assertEquals(a.id, b.id)
        assertEquals("https://example.com/story/1", a.canonicalUrl)
    }

    @Test
    fun idIsStableAcrossCallsAndDistinctBetweenArticles() {
        assertEquals(Ingest.idFor("https://example.com/a"), Ingest.idFor("https://example.com/a"))
        assertNotEquals(Ingest.idFor("https://example.com/a"), Ingest.idFor("https://example.com/b"))
        assertEquals(24, Ingest.idFor("https://example.com/a").length)
    }

    @Test
    fun keepsTheOriginalLinkForOpening() {
        val raw = "https://www.example.com/story/1?utm_source=rss"
        assertEquals(raw, Ingest.toArticle(item(link = raw), feed, now).link)
    }

    @Test
    fun taggingReadsTitleAndSummaryTogether() {
        val article = Ingest.toArticle(
            item(title = "Board approves large order", summary = "Reliance Industries said..."),
            feed,
            now,
        )
        assertEquals(listOf("RELIANCE"), article.symbols)
    }

    @Test
    fun fallsBackToFetchTimeWhenTheFeedOmitsADate() {
        val article = Ingest.toArticle(item(published = null), feed, now)
        assertEquals(now, article.publishedAt)
        // The flag records that the timestamp is ours, not the publisher's.
        assertFalse(article.hadPublishedDate)
    }

    @Test
    fun clampsFutureTimestampsToFetchTime() {
        // Without this a future-dated item scores full recency forever, because the
        // ranker floors negative age at zero.
        val article = Ingest.toArticle(item(published = now + 90 * minute), feed, now)
        assertEquals(now, article.publishedAt)
    }

    @Test
    fun clampsAbsurdlyOldTimestampsToFetchTime() {
        // Feeds that emit epoch zero on every item would otherwise be pruned on arrival.
        val article = Ingest.toArticle(item(published = 0L), feed, now)
        assertEquals(now, article.publishedAt)
    }

    @Test
    fun prefersThePublisherNamedInsideTheEntry() {
        // For a Google News query feed, the feed's own name means nothing to a reader.
        assertEquals(
            "The Economic Times",
            Ingest.toArticle(item(sourceName = "The Economic Times"), feed, now).sourceName,
        )
        assertEquals("Order wins", Ingest.toArticle(item(sourceName = "  "), feed, now).sourceName)
        assertEquals("Order wins", Ingest.toArticle(item(sourceName = null), feed, now).sourceName)
    }

    @Test
    fun feedHintOnlyAppliesWhenNoRuleMatches() {
        val plain = Ingest.toArticle(item(title = "Company holds investor day"), feed, now)
        assertEquals(Category.ORDER_WIN, plain.category)

        val regulatory = Ingest.toArticle(item(title = "SEBI bars two entities"), feed, now)
        assertEquals(Category.REGULATORY, regulatory.category)
    }

    @Test
    fun resolvePublishedAtBoundaries() {
        assertEquals(now, Ingest.resolvePublishedAt(now, now))
        assertEquals(now - 1, Ingest.resolvePublishedAt(now - 1, now))
        assertEquals(now, Ingest.resolvePublishedAt(now + 1, now))
    }
}
