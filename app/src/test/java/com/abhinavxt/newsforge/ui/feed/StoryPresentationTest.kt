package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.model.ArticleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPresentationTest {

    private fun article(
        title: String = "Tata Steel bags Rs 2,000 crore order from Indian Railways",
        summary: String? = null,
        sourceName: String = "Mint",
        otherSources: List<String> = emptyList(),
    ) = ArticleSummary(
        id = "a",
        clusterId = "a",
        title = title,
        summary = summary,
        link = "https://example.com/story/1",
        sourceName = sourceName,
        category = Category.ORDER_WIN,
        tier = SourceTier.WIRE,
        publishedAt = 0L,
        symbols = listOf("TATASTEEL"),
        clusterSize = 3,
        otherSources = otherSources,
        read = false,
        saved = false,
    )

    @Test
    fun outletsLeadWithWhoBrokeIt() {
        val outlets = StoryPresentation.outlets(
            article(otherSources = listOf("Business Standard", "The Economic Times"))
        )
        assertEquals(listOf("Mint", "Business Standard", "The Economic Times"), outlets)
    }

    @Test
    fun outletsAreDeduplicatedCaseInsensitively() {
        // The same publisher arrives spelt differently depending on which feed found it.
        val outlets = StoryPresentation.outlets(
            article(otherSources = listOf("mint", "MINT", "Business Standard"))
        )
        assertEquals(listOf("Mint", "Business Standard"), outlets)
    }

    @Test
    fun outletsIgnoreBlanks() {
        assertEquals(
            listOf("Mint"),
            StoryPresentation.outlets(article(otherSources = listOf("  ", ""))),
        )
    }

    @Test
    fun aSummaryThatRepeatsTheHeadlineIsDropped() {
        // Feeds very often set description to the headline verbatim; showing it gives the
        // reader the same sentence twice and makes the sheet look broken.
        val title = "Tata Steel bags Rs 2,000 crore order from Indian Railways"
        assertNull(StoryPresentation.summaryOrNull(article(title = title, summary = title)))
        assertNull(
            StoryPresentation.summaryOrNull(
                article(title = title, summary = "$title - Mint")
            )
        )
    }

    @Test
    fun aRealSummaryIsKept() {
        val summary = "The three-year contract covers rails for the dedicated freight corridor."
        assertEquals(summary, StoryPresentation.summaryOrNull(article(summary = summary)))
    }

    @Test
    fun missingOrBlankSummaryIsNull() {
        assertNull(StoryPresentation.summaryOrNull(article(summary = null)))
        assertNull(StoryPresentation.summaryOrNull(article(summary = "   ")))
    }

    @Test
    fun longSummariesAreTruncatedOnAWordBoundary() {
        val long = "word ".repeat(200).trim()
        val result = StoryPresentation.summaryOrNull(article(summary = long))!!
        assertTrue(result.length <= 321)
        assertTrue(result.endsWith("…"))
        assertFalse(result.contains("  "))
    }

    @Test
    fun titleComparisonIgnoresPunctuationAndTrailingOutlet() {
        assertTrue(
            StoryPresentation.isEssentiallyTheTitle(
                "RBI holds repo rate at 6.5%.",
                "RBI holds repo rate at 6.5%",
            )
        )
        assertFalse(
            StoryPresentation.isEssentiallyTheTitle(
                "The MPC voted five to one, citing food inflation and a weaker rupee.",
                "RBI holds repo rate at 6.5%",
            )
        )
    }

    @Test
    fun shareTextCarriesHeadlineOutletAndLink() {
        val text = StoryPresentation.shareText(article())
        assertTrue(text.startsWith("Tata Steel bags"))
        assertTrue(text.contains("Mint"))
        assertTrue(text.endsWith("https://example.com/story/1"))
    }

    @Test
    fun aPriceMoveIsAlwaysSignedAndAlwaysToOneDecimal() {
        // Fixed width under a monospace face is the point: a column of these has to be
        // scannable down the list.
        assertEquals("+1.4%", StoryPresentation.changeLabel(1.44))
        assertEquals("-2.5%", StoryPresentation.changeLabel(-2.45))
        assertEquals("+12.0%", StoryPresentation.changeLabel(12.0))
    }

    @Test
    fun aMoveThatRoundsToNothingIsNotGivenADirection() {
        // "-0.0%" reads as a fall that did not happen.
        assertEquals("0.0%", StoryPresentation.changeLabel(-0.04))
        assertEquals("0.0%", StoryPresentation.changeLabel(0.0))
        assertEquals("0.0%", StoryPresentation.changeLabel(0.02))
    }
}
