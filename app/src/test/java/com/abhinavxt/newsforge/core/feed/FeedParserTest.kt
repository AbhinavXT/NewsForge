package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime

class FeedParserTest {

    @Test
    fun parsesRss20() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Moneycontrol Markets</title>
                <item>
                  <title>Tata Steel bags Rs 2,000 crore order</title>
                  <link>https://www.moneycontrol.com/news/business/a-1.html</link>
                  <description><![CDATA[<p>The company said the <b>order</b> is for three years.</p>]]></description>
                  <pubDate>Wed, 09 Sep 2026 14:32:00 +0530</pubDate>
                  <guid isPermaLink="false">mc-12345</guid>
                </item>
                <item>
                  <title>Second story</title>
                  <link>https://www.moneycontrol.com/news/business/a-2.html</link>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = FeedParser.parse(xml)
        assertEquals("Moneycontrol Markets", feed.title)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Tata Steel bags Rs 2,000 crore order", first.title)
        assertEquals("https://www.moneycontrol.com/news/business/a-1.html", first.link)
        assertEquals("The company said the order is for three years.", first.summary)
        assertEquals("mc-12345", first.guid)
        assertEquals(
            OffsetDateTime.parse("2026-09-09T14:32:00+05:30").toInstant().toEpochMilli(),
            first.publishedAtMillis,
        )

        // A missing pubDate must not drop the item; the repository substitutes fetch time.
        assertNull(feed.items[1].publishedAtMillis)
    }

    @Test
    fun parsesGoogleNewsShapeAndStripsPublisherSuffix() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>"order win" - Google News</title>
                <item>
                  <title>Reliance wins Rs 5,000 crore contract - The Economic Times</title>
                  <link>https://news.google.com/rss/articles/CBMiXWh0dHBz?oc=5</link>
                  <pubDate>Wed, 09 Sep 2026 09:20:00 GMT</pubDate>
                  <source url="https://economictimes.indiatimes.com">The Economic Times</source>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val item = FeedParser.parse(xml).items.single()
        assertEquals("Reliance wins Rs 5,000 crore contract", item.title)
        assertEquals("The Economic Times", item.sourceName)
    }

    @Test
    fun parsesAtomPreferringAlternateLink() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Wire</title>
              <entry>
                <title>RBI holds repo rate at 6.5%</title>
                <link rel="self" href="https://example.com/feed/entry/1"/>
                <link rel="alternate" href="https://example.com/story/1"/>
                <id>tag:example.com,2026:1</id>
                <summary>The MPC voted 5-1 to hold.</summary>
                <published>2026-09-09T09:02:00Z</published>
              </entry>
            </feed>
        """.trimIndent()

        val item = FeedParser.parse(xml).items.single()
        assertEquals("RBI holds repo rate at 6.5%", item.title)
        assertEquals("https://example.com/story/1", item.link)
        assertEquals("tag:example.com,2026:1", item.guid)
        assertNotNull(item.publishedAtMillis)
    }

    @Test
    fun parsesRss10Rdf() {
        val xml = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://example.com">
                <title>Example RDF</title>
              </channel>
              <item rdf:about="https://example.com/1">
                <title>Cabinet approves PLI scheme</title>
                <link>https://example.com/1</link>
                <dc:date>2026-09-09T14:32:00+05:30</dc:date>
              </item>
            </rdf:RDF>
        """.trimIndent()

        val feed = FeedParser.parse(xml)
        assertEquals("Example RDF", feed.title)
        val item = feed.items.single()
        assertEquals("Cabinet approves PLI scheme", item.title)
        // dc:date must be found despite the prefix, since the parser matches local names.
        assertEquals(
            OffsetDateTime.parse("2026-09-09T14:32:00+05:30").toInstant().toEpochMilli(),
            item.publishedAtMillis,
        )
    }

    @Test
    fun fallsBackToPermalinkGuidWhenLinkIsMissing() {
        val xml = """
            <rss version="2.0"><channel><item>
              <title>No link here</title>
              <guid isPermaLink="true">https://example.com/story/9</guid>
            </item></channel></rss>
        """.trimIndent()

        assertEquals("https://example.com/story/9", FeedParser.parse(xml).items.single().link)
    }

    @Test
    fun skipsItemsWithNoUsableLinkOrTitle() {
        val xml = """
            <rss version="2.0"><channel>
              <item><title>Untitled but linkless</title><guid>not-a-url</guid></item>
              <item><link>https://example.com/2</link></item>
              <item><title>Good</title><link>https://example.com/3</link></item>
            </channel></rss>
        """.trimIndent()

        val items = FeedParser.parse(xml).items
        assertEquals(1, items.size)
        assertEquals("Good", items.single().title)
    }

    @Test
    fun decodesDoubleEscapedEntities() {
        val xml = """
            <rss version="2.0"><channel><item>
              <title>Dr Reddy&amp;#39;s &amp;amp; Cipla in talks</title>
              <link>https://example.com/4</link>
            </item></channel></rss>
        """.trimIndent()

        assertEquals(
            "Dr Reddy's & Cipla in talks",
            FeedParser.parse(xml).items.single().title,
        )
    }

    @Test
    fun throwsOnMalformedXml() {
        try {
            FeedParser.parse("<rss><channel><item><title>unclosed")
            fail("expected FeedParseException")
        } catch (expected: FeedParseException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    @Test
    fun throwsOnHtmlErrorPageServedInsteadOfFeed() {
        // Publishers return an HTML 404 body with a 200 status often enough to matter.
        try {
            FeedParser.parse("<html><body><h1>404 Not Found</h1></body></html>")
            fail("expected FeedParseException")
        } catch (expected: FeedParseException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    @Test
    fun stripPublisherSuffixLeavesUnrelatedTitlesAlone() {
        assertEquals(
            "Reliance wins contract",
            FeedParser.stripPublisherSuffix("Reliance wins contract - Mint", "Mint"),
        )
        assertEquals(
            "Reliance wins contract",
            FeedParser.stripPublisherSuffix("Reliance wins contract", "Mint"),
        )
        // A hyphen that is part of the headline must survive.
        assertEquals(
            "Rate cut - or not",
            FeedParser.stripPublisherSuffix("Rate cut - or not", "Mint"),
        )
    }
}
