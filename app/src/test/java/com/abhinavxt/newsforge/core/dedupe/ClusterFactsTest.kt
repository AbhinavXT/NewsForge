package com.abhinavxt.newsforge.core.dedupe

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterFactsTest {

    @Test
    fun theFilingDefinesTheStoryEvenWhenTheRewriteArrivedFirst() {
        // The case this exists for: a wire rewrite lands two minutes ahead of the filing
        // it was written from, becomes the anchor, and would otherwise define the story
        // as aggregator news for as long as it lives.
        assertEquals(
            SourceTier.OFFICIAL,
            ClusterFacts.strongestTier("WIRE,OFFICIAL,AGGREGATOR", SourceTier.WIRE),
        )
    }

    @Test
    fun theStrongestCategoryWins() {
        assertEquals(
            Category.REGULATORY,
            ClusterFacts.strongestCategory("OTHER,RESULTS,REGULATORY", Category.OTHER),
        )
        // By weight, not by the order they happen to arrive in.
        assertEquals(
            Category.REGULATORY,
            ClusterFacts.strongestCategory("REGULATORY,OTHER", Category.OTHER),
        )
    }

    @Test
    fun aSingleMemberClusterKeepsItsOwnValues() {
        assertEquals(SourceTier.WIRE, ClusterFacts.strongestTier("WIRE", SourceTier.WIRE))
        assertEquals(
            Category.DIVIDEND,
            ClusterFacts.strongestCategory("DIVIDEND", Category.DIVIDEND),
        )
    }

    @Test
    fun unknownNamesFallBackRatherThanCrashing() {
        // A downgrade can leave a name in the column that this build no longer has.
        assertEquals(
            SourceTier.AGGREGATOR,
            ClusterFacts.strongestTier("SOMETHING_ELSE", SourceTier.AGGREGATOR),
        )
        assertEquals(
            Category.OTHER,
            ClusterFacts.strongestCategory("NOT_A_CATEGORY", Category.OTHER),
        )
        // A mix keeps whatever it could read.
        assertEquals(
            SourceTier.OFFICIAL,
            ClusterFacts.strongestTier("GONE,OFFICIAL", SourceTier.AGGREGATOR),
        )
    }

    @Test
    fun nullAndBlankFallBack() {
        assertEquals(SourceTier.WIRE, ClusterFacts.strongestTier(null, SourceTier.WIRE))
        assertEquals(SourceTier.WIRE, ClusterFacts.strongestTier("", SourceTier.WIRE))
        assertEquals(Category.MACRO, ClusterFacts.strongestCategory(" , ", Category.MACRO))
    }

    @Test
    fun spacingAroundTheSeparatorIsTolerated() {
        // GROUP_CONCAT emits no spaces, but nothing in the schema guarantees that and a
        // stray one would silently drop a member from the comparison.
        assertEquals(
            SourceTier.OFFICIAL,
            ClusterFacts.strongestTier(" WIRE , OFFICIAL ", SourceTier.AGGREGATOR),
        )
    }
}
