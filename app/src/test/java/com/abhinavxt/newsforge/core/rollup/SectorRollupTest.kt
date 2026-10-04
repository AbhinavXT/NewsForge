package com.abhinavxt.newsforge.core.rollup

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.quote.SectorBreadth
import com.abhinavxt.newsforge.core.tag.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorRollupTest {

    private var next = 0

    private fun story(
        sectors: List<Sector>,
        score: Double,
        symbols: List<String> = emptyList(),
        clusterId: String = "c${next++}",
        watchlisted: Boolean = false,
        title: String = "story $clusterId",
    ) = RollupStory(
        clusterId = clusterId,
        title = title,
        sectors = sectors,
        symbols = symbols,
        category = Category.OTHER,
        score = score,
        watchlisted = watchlisted,
    )

    @Test
    fun hottestSectorLeads() {
        val rollups = SectorRollups.of(
            listOf(
                story(listOf(Sector.PHARMA), 4.0),
                story(listOf(Sector.IT), 1.0),
                story(listOf(Sector.PHARMA), 3.0),
            )
        )
        assertEquals(Sector.PHARMA, rollups.first().sector)
        assertEquals(7.0, rollups.first().heat, 0.0001)
    }

    @Test
    fun oneStoryCarriedByEightOutletsCountsOnce() {
        // Otherwise heat measures syndication, and the sector with the most rewrites of
        // one wire item wins every morning.
        val syndicated = (1..8).map {
            story(listOf(Sector.IT), score = 2.0, clusterId = "same")
        }
        val rollup = SectorRollups.of(syndicated).single()
        assertEquals(1, rollup.stories)
        assertEquals(2.0, rollup.heat, 0.0001)
    }

    @Test
    fun aClusterContributesItsStrongestMember() {
        val rollup = SectorRollups.of(
            listOf(
                story(listOf(Sector.AUTO), score = 1.0, clusterId = "same"),
                story(listOf(Sector.AUTO), score = 5.0, clusterId = "same", title = "best"),
            )
        ).single()
        assertEquals(5.0, rollup.heat, 0.0001)
        assertEquals("best", rollup.lead?.title)
    }

    @Test
    fun oneBigStoryOutranksADozenSmallOnes() {
        // The cap on contributing stories is the whole point: twelve recycled previews
        // should not outweigh one filing that matters.
        val loud = listOf(story(listOf(Sector.DEFENCE), 9.0))
        val many = (1..12).map { story(listOf(Sector.FMCG), 1.0) }
        val rollups = SectorRollups.of(loud + many)
        assertEquals(Sector.DEFENCE, rollups.first().sector)
        // Only the strongest three of the twelve contribute.
        val fmcg = rollups.first { it.sector == Sector.FMCG }
        assertEquals(12, fmcg.stories)
        assertEquals(3.0, fmcg.heat, 0.0001)
    }

    @Test
    fun aStoryTouchingTwoSectorsCountsInBoth() {
        // Crude moving is an oil story and an input-cost story at the same time.
        val rollups = SectorRollups.of(
            listOf(story(listOf(Sector.OIL_GAS, Sector.CHEMICALS), 3.0))
        )
        assertEquals(2, rollups.size)
        assertTrue(rollups.all { it.stories == 1 })
    }

    @Test
    fun symbolsAreRankedByHowOftenTheyAppear() {
        val rollups = SectorRollups.of(
            listOf(
                story(listOf(Sector.METALS), 3.0, symbols = listOf("TATASTEEL", "SAIL")),
                story(listOf(Sector.METALS), 2.0, symbols = listOf("TATASTEEL")),
                story(listOf(Sector.METALS), 1.0, symbols = listOf("TATASTEEL", "JSWSTEEL")),
            )
        )
        assertEquals(
            listOf("TATASTEEL", "JSWSTEEL", "SAIL"),
            rollups.single().symbols,
        )
    }

    @Test
    fun watchlistExposureIsCarriedUp() {
        val rollups = SectorRollups.of(
            listOf(
                story(listOf(Sector.BANKING), 2.0),
                story(listOf(Sector.BANKING), 1.0, watchlisted = true),
                story(listOf(Sector.IT), 1.0),
            )
        )
        assertTrue(rollups.first { it.sector == Sector.BANKING }.watchlisted)
        assertFalse(rollups.first { it.sector == Sector.IT }.watchlisted)
    }

    @Test
    fun priceContextIsAttachedWhereItExists() {
        val breadth = SectorBreadth(
            sector = Sector.PHARMA,
            medianChangePercent = 2.4,
            advances = 6,
            declines = 1,
            unchanged = 0,
            constituents = 7,
            atMillis = 1L,
        )
        val market = MarketBreadth("NIFTY 500", 1.1, 300, 190, 10, 1L)
        val rollups = SectorRollups.of(
            listOf(
                story(listOf(Sector.PHARMA), 3.0),
                story(listOf(Sector.IT), 2.0),
            ),
            breadths = mapOf(Sector.PHARMA to breadth),
        )
        val pharma = rollups.first { it.sector == Sector.PHARMA }
        assertEquals(1.3, pharma.relativeMove(market)!!, 0.0001)
        // No quote for IT is the ordinary case, not an error.
        assertNull(rollups.first { it.sector == Sector.IT }.breadth)
        assertNull(rollups.first { it.sector == Sector.IT }.relativeMove(market))
    }

    @Test
    fun aSectorNobodyWroteAboutIsAbsent() {
        // Eighteen rows of "nothing happened" is not a summary of what happened.
        val rollups = SectorRollups.of(listOf(story(listOf(Sector.IT), 1.0)))
        assertEquals(1, rollups.size)
    }

    @Test
    fun equalHeatOrdersStably() {
        val rollups = SectorRollups.of(
            listOf(
                story(listOf(Sector.REALTY), 1.0),
                story(listOf(Sector.BANKING), 1.0),
            )
        ).map { it.sector }
        // Enum order breaks the tie, so a quiet morning does not produce a list that
        // reshuffles itself on every recomposition.
        assertEquals(listOf(Sector.BANKING, Sector.REALTY), rollups)
    }

    @Test
    fun noStoriesIsAnEmptyRollupNotACrash() {
        assertTrue(SectorRollups.of(emptyList()).isEmpty())
    }
}
