package com.abhinavxt.newsforge.core.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlineTest {

    @Test
    fun dropsStopwordsPunctuationAndCurrencyFiller() {
        assertEquals(
            listOf("reliance", "bag", "5000", "crore", "order", "railway"),
            Headline.tokenize("Reliance bags a Rs 5,000 crore order from the Railways!"),
        )
    }

    @Test
    fun normalisesDigitSeparatorsAndPossessives() {
        assertEquals(
            Headline.tokenize("order worth 5000 crore"),
            Headline.tokenize("order worth 5,000 crore"),
        )
        assertEquals(listOf("reddy", "profit"), Headline.tokenize("Reddy's profit"))
    }

    @Test
    fun foldsSimplePluralsButLeavesShortAndDoubleSWordsAlone() {
        // Outlets alternate freely between "Railways" and "Railway"; without folding,
        // that counts as a disagreement on the word most likely to be shared.
        assertEquals(Headline.tokenize("railway order"), Headline.tokenize("railways order"))
        assertEquals(listOf("gas", "price"), Headline.tokenize("gas prices"))
        assertEquals(listOf("focus"), Headline.tokenize("focus"))
        assertEquals(listOf("cross"), Headline.tokenize("cross"))
    }

    @Test
    fun tokenSetIsUnordered() {
        assertEquals(
            Headline.tokenSet("Infosys raises guidance"),
            Headline.tokenSet("guidance raises Infosys"),
        )
    }

    @Test
    fun sameStoryFromTwoOutletsClearsBothThresholds() {
        val a = Headline.tokenSet("Tata Steel bags Rs 2,000 crore order from Indian Railways")
        val b = Headline.tokenSet("Tata Steel wins Rs 2,000 crore order from Indian Railways")
        assertTrue(
            "containment was ${Similarity.containment(a, b)}",
            Similarity.containment(a, b) >= Clusterer.MIN_CONTAINMENT,
        )
        assertTrue(
            "jaccard was ${Similarity.jaccard(a, b)}",
            Similarity.jaccard(a, b) >= Clusterer.MIN_JACCARD,
        )
    }

    @Test
    fun differentPhrasingOfTheSameEventStillClearsThresholds() {
        val a = Headline.tokenSet("Tata Steel bags Rs 2,000 crore order from Indian Railways")
        val b = Headline.tokenSet("Tata Steel secures Rs 2,000-crore railway order")
        assertTrue(
            "containment was ${Similarity.containment(a, b)}",
            Similarity.containment(a, b) >= Clusterer.MIN_CONTAINMENT,
        )
    }

    @Test
    fun unrelatedHeadlinesShareAlmostNothing() {
        val a = Headline.tokenSet("RBI holds repo rate at 6.5% as inflation cools")
        val b = Headline.tokenSet("Maruti Suzuki launches new compact SUV in October")
        assertTrue(Similarity.jaccard(a, b) < Clusterer.MIN_JACCARD)
    }

    @Test
    fun similarityIsSymmetricAndOneForSelf() {
        val a = Headline.tokenSet("Infosys raises FY27 revenue guidance")
        val b = Headline.tokenSet("Wipro cuts headcount in consulting arm")
        assertEquals(Similarity.jaccard(a, b), Similarity.jaccard(b, a), 1e-9)
        assertEquals(1.0, Similarity.jaccard(a, a), 1e-9)
        assertEquals(1.0, Similarity.containment(a, a), 1e-9)
    }

    @Test
    fun emptySetsScoreZeroRatherThanDividingByZero() {
        val a = Headline.tokenSet("Infosys raises guidance")
        assertEquals(0.0, Similarity.jaccard(emptySet(), a), 1e-9)
        assertEquals(0.0, Similarity.containment(a, emptySet()), 1e-9)
        assertEquals(0.0, Similarity.jaccard(emptySet(), emptySet()), 1e-9)
    }

    @Test
    fun containmentIsMoreForgivingThanJaccardWhenOneSideAddsDetail() {
        val short = Headline.tokenSet("Infosys raises revenue guidance")
        val long = Headline.tokenSet(
            "Infosys raises FY27 revenue guidance after strong deal wins in Europe"
        )
        assertTrue(Similarity.containment(short, long) > Similarity.jaccard(short, long))
    }
}
