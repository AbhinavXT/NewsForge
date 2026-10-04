package com.abhinavxt.newsforge.core.dedupe

import com.abhinavxt.newsforge.core.tag.SeedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClustererTest {

    private val base = 1_757_400_000_000L // arbitrary fixed instant
    private val minute = 60_000L

    /** Builds an input the way the real pipeline will: tag first, then cluster. */
    private fun input(
        id: String,
        title: String,
        url: String = "https://example.com/$id",
        offsetMinutes: Long = 0,
    ) = ClusterInput(
        id = id,
        canonicalUrl = UrlCanonicalizer.canonicalize(url),
        tokens = Headline.tokenSet(title),
        symbols = SeedSymbols.LEXICON.match(title).toSet(),
        publishedAtMillis = base + offsetMinutes * minute,
    )

    private fun memberOf(item: ClusterInput, clusterId: String) = ClusterMember(
        clusterId, item.canonicalUrl, item.tokens, item.symbols, item.publishedAtMillis
    )

    @Test
    fun mergesTheSameStoryFromDifferentOutlets() {
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Tata Steel bags Rs 2,000 crore order from Indian Railways"),
                input("b", "Tata Steel wins Rs 2,000 crore order from Indian Railways", offsetMinutes = 12),
            )
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf("a", "b"), clusters.single().memberIds)
    }

    @Test
    fun clusterIdIsTheEarliestMember() {
        val clusters = Clusterer.cluster(
            listOf(
                input("late", "Tata Steel wins Rs 2,000 crore order from Indian Railways", offsetMinutes = 30),
                input("early", "Tata Steel bags Rs 2,000 crore order from Indian Railways", offsetMinutes = 0),
            )
        )
        // Whoever published first anchors the cluster, so the card can credit the break.
        assertEquals("early", clusters.single().id)
    }

    @Test
    fun keepsDifferentCompaniesApartDespiteNearIdenticalWording() {
        // The headline pair that killed the SimHash approach: one word apart, and text
        // similarity alone rates it higher than a genuine same-story pair.
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Tata Steel bags Rs 2,000 crore order from Indian Railways"),
                input("b", "JSW Steel bags Rs 2,000 crore order from Indian Railways", offsetMinutes = 5),
            )
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun untaggedItemsNeverConflictOnSymbols() {
        // Absence of a symbol means the lexicon did not recognise the company, not that
        // the story is about nothing — so an untagged item must stay mergeable.
        assertFalse(Clusterer.symbolsConflict(emptySet(), setOf("RELIANCE")))
        assertFalse(Clusterer.symbolsConflict(setOf("RELIANCE"), emptySet()))
        assertFalse(Clusterer.symbolsConflict(emptySet(), emptySet()))
        assertTrue(Clusterer.symbolsConflict(setOf("TATASTEEL"), setOf("JSWSTEEL")))
        // A partial overlap is agreement, not conflict.
        assertFalse(
            Clusterer.symbolsConflict(setOf("TATASTEEL", "SAIL"), setOf("TATASTEEL"))
        )
    }

    @Test
    fun doesNotMergeAcrossTheTimeWindow() {
        val outsideWindow = Clusterer.DEFAULT_WINDOW_MS / minute + 60
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Tata Steel bags Rs 2,000 crore order from Indian Railways"),
                input(
                    "b",
                    "Tata Steel wins Rs 2,000 crore order from Indian Railways",
                    offsetMinutes = outsideWindow,
                ),
            )
        )
        // Days later the same wording is a new event, not a duplicate.
        assertEquals(2, clusters.size)
    }

    @Test
    fun sameCanonicalUrlMergesEvenWhenHeadlineWasRewritten() {
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Markets open higher", url = "https://example.com/live?utm_source=rss"),
                input(
                    "b",
                    "Sensex jumps 400 points as banks lead the rally",
                    url = "https://www.example.com/live/",
                    offsetMinutes = 40,
                ),
            )
        )
        assertEquals(1, clusters.size)
    }

    @Test
    fun shortHeadlinesRequireAnIdenticalTokenSet() {
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Sensex falls sharply"),
                input("b", "Nifty falls sharply", offsetMinutes = 3),
            )
        )
        // Two of three tokens shared is not enough signal to risk a merge.
        assertEquals(2, clusters.size)
    }

    @Test
    fun identicalShortHeadlinesFromDifferentUrlsStillMerge() {
        val clusters = Clusterer.cluster(
            listOf(
                input("a", "Sensex falls sharply", url = "https://one.example.com/x"),
                input("b", "Sensex falls sharply", url = "https://two.example.com/y", offsetMinutes = 3),
            )
        )
        assertEquals(1, clusters.size)
    }

    @Test
    fun assignReturnsNullWhenNothingIsClose() {
        val existing = input("old", "RBI holds repo rate at 6.5% as inflation cools")
        val fresh = input("x", "Maruti Suzuki launches new compact SUV in October", offsetMinutes = 5)
        assertNull(Clusterer.assign(fresh, listOf(memberOf(existing, "c1"))))
    }

    @Test
    fun assignIsIncrementalAndAgreesWithBatchClustering() {
        val items = listOf(
            input("a", "Tata Steel bags Rs 2,000 crore order from Indian Railways"),
            input("b", "Tata Steel wins Rs 2,000 crore order from Indian Railways", offsetMinutes = 8),
            input("c", "Maruti Suzuki launches new compact SUV in October", offsetMinutes = 9),
        )
        val seen = ArrayList<ClusterMember>()
        val assigned = items.associate { item ->
            val clusterId = Clusterer.assign(item, seen) ?: item.id
            seen.add(memberOf(item, clusterId))
            item.id to clusterId
        }

        val batch = Clusterer.cluster(items)
            .flatMap { cluster -> cluster.memberIds.map { it to cluster.id } }
            .toMap()
        assertEquals(batch, assigned)
    }

    @Test
    fun emptyInputProducesNoClusters() {
        assertEquals(emptyList<Cluster>(), Clusterer.cluster(emptyList()))
    }
}
