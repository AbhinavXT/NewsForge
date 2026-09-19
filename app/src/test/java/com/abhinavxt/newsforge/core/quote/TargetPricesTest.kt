package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The least certain thing on the research screen, and so the most tested.
 *
 * Every other figure there is fetched. This one is read out of prose somebody wrote for
 * humans, and the ways it goes wrong are specific: a crore figure in the same headline, a
 * government target that is not a share price, a percentage sitting next to the word.
 */
class TargetPricesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    // ----------------------------------------------------------------- parse

    @Test
    fun readsTheUsualShapes() {
        assertEquals(4200.0, TargetPrices.parse("Motilal raises target price to Rs 4,200")!!, 1e-9)
        assertEquals(2100.0, TargetPrices.parse("Jefferies: price target of ₹2,100")!!, 1e-9)
        assertEquals(1850.0, TargetPrices.parse("CLSA sets Rs 1,850 target for HAL")!!, 1e-9)
        assertEquals(512.5, TargetPrices.parse("Target price: Rs 512.50")!!, 1e-9)
    }

    /** The company name usually sits between the phrase and the figure. */
    @Test
    fun reachesPastACompanyName() {
        assertEquals(240.0, TargetPrices.parse("Kotak cuts price target on Zomato to Rs 240")!!, 1e-9)
        assertEquals(520.0, TargetPrices.parse("Emkay ups target price on BEL to ₹520")!!, 1e-9)
    }

    /** Two figures, one target. It has to take the one attached to the phrase. */
    @Test
    fun takesTheFigureAttachedToThePhrase() {
        assertEquals(
            4200.0,
            TargetPrices.parse("Motilal raises target price to Rs 4,200 after Rs 8,000 crore order")!!,
            1e-9,
        )
    }

    /**
     * A crore figure is not a share price.
     *
     * This is the failure that would put ₹50,000 on a stock trading at 400, and market
     * copy is full of it — order values, revenue goals, divestment targets.
     */
    @Test
    fun aScaledFigureIsNotATarget() {
        assertNull(TargetPrices.parse("Government sets divestment target of Rs 50,000 crore"))
        assertNull(TargetPrices.parse("Company targets Rs 10,000 crore revenue by FY28"))
        assertNull(TargetPrices.parse("Adani targets 45 GW capacity, invests Rs 2 lakh crore"))
    }

    @Test
    fun unrelatedRupeeFiguresAreIgnored() {
        assertNull(TargetPrices.parse("L&T bags Rs 5,000 crore order from NHAI"))
        assertNull(TargetPrices.parse("Reliance Q2 profit rises to Rs 18,000 crore"))
        assertNull(TargetPrices.parse("HAL, BEL, L&T: CLSA sees up to 35% upside"))
    }

    // ------------------------------------------------------------- consensus

    private fun note(price: Double, daysAgo: Long = 1) =
        BrokerTarget(price, now - daysAgo * day, "Moneycontrol")

    @Test
    fun oneNoteIsNotAConsensus() {
        assertNull(TargetPrices.consensus(listOf(note(4200.0)), now))
        assertNull(TargetPrices.consensus(emptyList(), now))
    }

    @Test
    fun theBandSpansTheNotes() {
        val c = TargetPrices.consensus(
            listOf(note(4200.0), note(3800.0), note(4500.0)),
            now,
        )!!
        assertEquals(3800.0, c.low, 1e-9)
        assertEquals(4500.0, c.high, 1e-9)
        assertEquals(4200.0, c.median, 1e-9)
        assertEquals(3, c.count)
    }

    /** One house well outside the rest widens the band without dragging the middle. */
    @Test
    fun anOutlierMovesTheBandNotTheMedian() {
        val c = TargetPrices.consensus(
            listOf(note(4200.0), note(4100.0), note(4300.0), note(12000.0), note(4250.0)),
            now,
        )!!
        assertEquals(12000.0, c.high, 1e-9)
        assertEquals(4250.0, c.median, 1e-9)
    }

    @Test
    fun staleNotesAreDropped() {
        val old = note(9000.0, daysAgo = 200)
        val c = TargetPrices.consensus(listOf(note(4200.0), note(4300.0), old), now)!!
        assertEquals(2, c.count)
        assertEquals(4300.0, c.high, 1e-9)
    }

    @Test
    fun droppingStaleNotesCanLeaveTooFew() {
        assertNull(
            TargetPrices.consensus(
                listOf(note(4200.0, daysAgo = 200), note(4300.0, daysAgo = 300)),
                now,
            )
        )
    }

    @Test
    fun theNewestNoteIsReported() {
        val c = TargetPrices.consensus(listOf(note(4200.0, 5), note(4300.0, 2)), now)!!
        assertEquals(now - 2 * day, c.newestAt)
        assertNotNull(c)
    }
}
