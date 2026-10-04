package com.abhinavxt.newsforge.core.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadlineNumbersTest {

    @Test
    fun readsTheUnitsIndianMarketCopyActuallyUses() {
        assertEquals(1200.0, HeadlineNumbers.croreIn("bags Rs 1,200 crore order")!!, 0.001)
        assertEquals(450.0, HeadlineNumbers.croreIn("Rs 450 cr contract from NHAI")!!, 0.001)
        assertEquals(0.5, HeadlineNumbers.croreIn("penalty of Rs 50 lakh")!!, 0.001)
        assertEquals(100.0, HeadlineNumbers.croreIn("$1 billion deal")!!, 0.001)
        assertEquals(20.0, HeadlineNumbers.croreIn("200 mn raise")!!, 0.001)
    }

    @Test
    fun lakhCroreIsNotALakh() {
        // "Rs 2 lakh crore" is 200,000 crore, and matching "lakh" first would report 0.02.
        assertEquals(200_000.0, HeadlineNumbers.croreIn("Rs 2 lakh crore package")!!, 0.001)
    }

    @Test
    fun theLargestAmountWins() {
        // Headlines carry the deal and its comparison; the deal is the bigger one far
        // more often than not.
        assertEquals(
            1200.0,
            HeadlineNumbers.croreIn("bags Rs 1,200 crore order, up from Rs 300 crore")!!,
            0.001,
        )
    }

    @Test
    fun decimalsAndSeparatorsSurvive() {
        assertEquals(1234.5, HeadlineNumbers.croreIn("Rs 1,234.5 crore")!!, 0.001)
    }

    @Test
    fun aHeadlineWithNoFigureSaysSo() {
        // Null rather than zero: no amount is not an amount of nothing, and the feature
        // builder depends on being able to tell them apart.
        assertNull(HeadlineNumbers.croreIn("Outcome of board meeting"))
        assertNull(HeadlineNumbers.croreIn(""))
        assertNull(HeadlineNumbers.croreIn(null))
    }

    @Test
    fun aBareNumberIsNotAnAmount() {
        // Without a magnitude word there is nothing to scale, and guessing crore would
        // turn every quarter number and every year into a billion-rupee deal.
        assertNull(HeadlineNumbers.croreIn("Q1 FY2027 results"))
        assertNull(HeadlineNumbers.croreIn("appoints 3 directors"))
    }

    @Test
    fun percentagesAreReadTheSameWay() {
        assertEquals(12.0, HeadlineNumbers.percentIn("revenue up 12%")!!, 0.001)
        assertEquals(8.5, HeadlineNumbers.percentIn("margin of 8.5 per cent")!!, 0.001)
        assertEquals(20.0, HeadlineNumbers.percentIn("up 5% to 20 percent")!!, 0.001)
        assertNull(HeadlineNumbers.percentIn("no figures here"))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(500.0, HeadlineNumbers.croreIn("RS 500 CRORE ORDER")!!, 0.001)
    }
}
