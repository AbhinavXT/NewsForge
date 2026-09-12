package com.abhinavxt.newsforge.core

import com.abhinavxt.newsforge.core.dedupe.ClusterInput
import com.abhinavxt.newsforge.core.dedupe.Clusterer
import com.abhinavxt.newsforge.core.dedupe.Headline
import com.abhinavxt.newsforge.core.dedupe.UrlCanonicalizer
import com.abhinavxt.newsforge.core.feed.FeedParser
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.MarketPhase
import com.abhinavxt.newsforge.core.rank.RankInput
import com.abhinavxt.newsforge.core.rank.Ranker
import com.abhinavxt.newsforge.core.tag.Categorizer
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Walks one realistic feed through every stage in the order the repository will.
 *
 * The unit tests prove each piece works alone; this proves they compose, and it pins the
 * pipeline's order of operations — tag before cluster, cluster before rank — which is not
 * arbitrary: clustering depends on symbols, and ranking depends on cluster size.
 */
class PipelineSmokeTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    /** 2026-09-09 is a Wednesday; 10:30 is mid-session. */
    private val now = ist("2026-09-09T10:30:00")

    private val feedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Test wire</title>
            <item>
              <title>SEBI bars two entities from the securities market - The Economic Times</title>
              <link>https://example.com/sebi-order?utm_source=rss</link>
              <pubDate>Wed, 09 Sep 2026 10:25:00 +0530</pubDate>
              <source url="https://economictimes.indiatimes.com">The Economic Times</source>
            </item>
            <item>
              <title>Tata Steel bags Rs 2,000 crore order from Indian Railways</title>
              <link>https://www.livemint.com/tata-order/</link>
              <pubDate>Wed, 09 Sep 2026 10:20:00 +0530</pubDate>
            </item>
            <item>
              <title>Tata Steel wins Rs 2,000 crore order from Indian Railways</title>
              <link>https://www.moneycontrol.com/tata-order.html</link>
              <pubDate>Wed, 09 Sep 2026 10:22:00 IST</pubDate>
            </item>
            <item>
              <title>JSW Steel bags Rs 2,000 crore order from Indian Railways</title>
              <link>https://www.business-standard.com/jsw-order</link>
              <pubDate>Wed, 09 Sep 2026 10:21:00 +0530</pubDate>
            </item>
            <item>
              <title>Cabinet approves PLI scheme for semiconductors</title>
              <link>https://example.com/pli-cabinet</link>
              <pubDate>Wed, 09 Sep 2026 09:50:00 +0530</pubDate>
            </item>
            <item>
              <title>Maruti Suzuki launches new compact SUV in October</title>
              <link>https://example.com/maruti-suv</link>
              <pubDate>Wed, 09 Sep 2026 06:00:00 +0530</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private data class Processed(
        val title: String,
        val category: Category,
        val symbols: Set<String>,
        val input: ClusterInput,
    )

    private fun process(): List<Processed> =
        FeedParser.parse(feedXml).items.mapIndexed { index, item ->
            val symbols = SeedSymbols.LEXICON.match(item.title).toSet()
            Processed(
                title = item.title,
                category = Categorizer.categorize(item.title, item.summary),
                symbols = symbols,
                input = ClusterInput(
                    id = "i$index",
                    canonicalUrl = UrlCanonicalizer.canonicalize(item.link),
                    tokens = Headline.tokenSet(item.title),
                    symbols = symbols,
                    // publishedAtMillis is never null here, but the repository falls back
                    // to fetch time when a feed omits the date.
                    publishedAtMillis = item.publishedAtMillis ?: now,
                ),
            )
        }

    @Test
    fun sessionPhaseIsOpenForTheFixtureInstant() {
        assertEquals(MarketPhase.OPEN, MarketClock.phase(now))
    }

    @Test
    fun taggingAndCategorisationAgreeWithTheHeadlines() {
        val processed = process()
        assertEquals(6, processed.size)

        assertEquals(Category.REGULATORY, processed[0].category)
        assertTrue("regulator names should not be tagged as issuers", processed[0].symbols.isEmpty())

        assertEquals(Category.ORDER_WIN, processed[1].category)
        assertEquals(setOf("TATASTEEL"), processed[1].symbols)
        assertEquals(setOf("JSWSTEEL"), processed[3].symbols)

        assertEquals(Category.POLICY, processed[4].category)
        assertEquals(setOf("MARUTI"), processed[5].symbols)
    }

    @Test
    fun sixItemsCollapseToFiveStories() {
        val clusters = Clusterer.cluster(process().map { it.input })
        assertEquals(5, clusters.size)

        // The two Tata reports merge; the JSW one, one word apart, does not.
        val merged = clusters.single { it.memberIds.size > 1 }
        assertEquals(listOf("i1", "i2"), merged.memberIds)
    }

    @Test
    fun rankingPutsTheBroadlyCoveredFreshOrderOnTop() {
        val processed = process()
        val clusters = Clusterer.cluster(processed.map { it.input })
        val sizeOf = clusters.associate { it.id to it.memberIds.size }

        val ranked = clusters.map { cluster ->
            val anchor = processed.first { it.input.id == cluster.id }
            val score = Ranker.score(
                RankInput(
                    category = anchor.category,
                    tier = SourceTier.WIRE,
                    publishedAtMillis = anchor.input.publishedAtMillis,
                    symbolCount = anchor.symbols.size,
                    clusterSize = sizeOf.getValue(cluster.id),
                ),
                nowMillis = now,
                phase = MarketPhase.OPEN,
            )
            anchor.title to score
        }.sortedByDescending { it.second }

        val order = ranked.map { it.first }
        assertTrue("Tata order should lead: $order", order[0].startsWith("Tata Steel bags"))
        assertTrue("JSW order should follow: $order", order[1].startsWith("JSW Steel bags"))
        assertTrue("SEBI action should be third: $order", order[2].startsWith("SEBI bars"))

        // The overnight SUV launch is four and a half hours stale mid-session; age has to
        // sink it regardless of the recognised symbol.
        assertTrue("stale launch should be last: $order", order.last().startsWith("Maruti"))
    }
}
