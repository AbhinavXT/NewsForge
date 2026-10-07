package com.abhinavxt.newsforge.core.world

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.notify.AlertCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordsTest {

    @Test
    fun matchesWholeWordsCaseInsensitively() {
        assertEquals("ISRO", Keywords.firstMatch(listOf("ISRO"), "isro launches new satellite", null))
        // "AI" must not match inside "said" or "Mumbai".
        assertNull(Keywords.firstMatch(listOf("AI"), "Mumbai mayor said the plan was ready", null))
        assertEquals("AI", Keywords.firstMatch(listOf("AI"), "New AI rules announced", null))
    }

    @Test
    fun matchesPhrasesAcrossExtraWhitespaceAndInTheSummary() {
        assertEquals(
            "Bengaluru metro",
            Keywords.firstMatch(listOf("Bengaluru metro"), "City update", "The Bengaluru  Metro line opens"),
        )
    }

    @Test
    fun punctuationInAKeywordStillHasBoundaries() {
        assertEquals("U.S.", Keywords.firstMatch(listOf("U.S."), "U.S. tariffs rise", null))
        assertEquals("C++", Keywords.firstMatch(listOf("C++"), "Why C++ still matters", null))
    }

    @Test
    fun normalizeRejectsBlankOverlongAndSymbolOnlyInput() {
        assertEquals("Bengaluru metro", Keywords.normalize("  Bengaluru   metro "))
        assertNull(Keywords.normalize("   "))
        assertNull(Keywords.normalize("!!"))
        assertNull(Keywords.normalize("x".repeat(Keywords.MAX_LENGTH + 1)))
    }

    private fun candidate(cluster: String, title: String, at: Long) = AlertCandidate(
        id = "$cluster-$at", clusterId = cluster, title = title, link = "https://example.com/$cluster",
        sourceName = "Outlet", category = Category.SCIENCE, tier = SourceTier.WIRE,
        publishedAt = at, symbols = emptyList(),
    )

    @Test
    fun keywordAlertsAreOnePerStoryNewestFirstAndCapped() {
        val arrivals = listOf(
            candidate("a", "ISRO tests engine", 1),
            candidate("a", "ISRO engine test succeeds", 2),
            candidate("b", "ISRO plans moon mission", 3),
            candidate("c", "ISRO budget raised", 4),
            candidate("d", "ISRO hires", 5),
            candidate("e", "Unrelated story", 6),
        )
        val alerts = KeywordAlerts.select(arrivals, listOf("ISRO"), emptySet(), firstSync = false)
        assertEquals(listOf("d", "c", "b"), alerts.map { it.candidate.clusterId })
    }

    @Test
    fun keywordAlertsSkipTheFirstSyncAndWhatAlreadyRang() {
        val arrivals = listOf(candidate("a", "ISRO launch", 1), candidate("b", "ISRO landing", 2))
        assertTrue(KeywordAlerts.select(arrivals, listOf("ISRO"), emptySet(), firstSync = true).isEmpty())
        assertEquals(
            listOf("a"),
            KeywordAlerts.select(arrivals, listOf("ISRO"), setOf("b"), firstSync = false)
                .map { it.candidate.clusterId },
        )
    }
}
