package com.abhinavxt.newsforge.core.ta

import com.abhinavxt.newsforge.core.quote.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The port of TickerForge's order-block maths, held to the same synthetic setups as its
 * own `tests/test_board.py`, so a zone drawn on the phone is the zone the desk ranks.
 */
class OrderBlocksTest {

    private fun bar(t: Int, o: Double, c: Double, hi: Double? = null, lo: Double? = null) =
        Candle(t.toLong(), o, hi ?: (maxOf(o, c) + 0.2), lo ?: (minOf(o, c) - 0.2), c, 1000.0)

    /** A sine-wave range: strict swing extremes, every cycle peaking at the same price. */
    private fun chop(n: Int = 32, base: Double = 100.0, t0: Int = 0, amp: Double = 0.8): MutableList<Candle> {
        val out = ArrayList<Candle>()
        var prev = base
        for (i in 0 until n) {
            val c = base + amp * sin((i + 1) * 2 * PI / 8)
            out += bar(t0 + i, prev, c, hi = maxOf(prev, c) + 0.2, lo = minOf(prev, c) - 0.2)
            prev = c
        }
        return out
    }

    /** Range, one bear bar (the block), a big bull bar through the high, then holding. */
    private fun bullishSetup(): Triple<MutableList<Candle>, Int, Int> {
        val c = chop(30)
        val t = c.size
        c += bar(t, 100.6, 100.0, hi = 100.8, lo = 99.7)
        c += bar(t + 1, 100.0, 104.0, hi = 104.2, lo = 99.9)
        for (k in 0 until 6) c += bar(t + 2 + k, 104 + k * 0.3, 104.3 + k * 0.3)
        return Triple(c, t, t + 1)
    }

    private fun displacements(c: List<Candle>) = OrderBlocks.findDisplacements(
        c, OrderBlocks.atr(c, OrderBlocks.ATR_LEN), OrderBlocks.swingPoints(c, 2, 2),
    )

    @Test
    fun `a big bar through the swing high is a displacement`() {
        val (c, _, d) = bullishSetup()
        val hits = displacements(c).filter { it.bullish }
        assertEquals(listOf(d), hits.map { it.index })
        assertTrue(hits.last().atrMultiple > OrderBlocks.K_ATR)
    }

    @Test
    fun `a big bar inside the range is not`() {
        val c = chop(30)
        val t = c.size
        c += bar(t, 97.0, 100.5, hi = 100.6, lo = 96.9)
        assertTrue(displacements(c).none { it.index == t })
    }

    @Test
    fun `a small bar breaking the high is not`() {
        val c = chop(30)
        val t = c.size
        c += bar(t, 100.6, 101.2)
        assertTrue(displacements(c).none { it.index == t })
    }

    @Test
    fun `the block is the last opposite-colour bar, and it is live`() {
        val (c, ob, _) = bullishSetup()
        val zones = OrderBlocks.analyze(c)!!
        val block = zones.bullishBlock!!
        assertEquals(ob, block.index)
        assertEquals(c[ob].high, block.top, 1e-9)
        assertEquals(c[ob].low, block.bottom, 1e-9)
        assertEquals(block, zones.active)
    }

    @Test
    fun `a retest keeps the block, trading through it kills it`() {
        val (c, ob, _) = bullishSetup()
        val top = c[ob].high
        val bottom = c[ob].low
        val t = c.size
        c += bar(t, 104.0, 100.9, lo = top - 0.05)
        assertNotNull(OrderBlocks.analyze(c)!!.bullishBlock)
        c += bar(t + 1, 100.9, 99.0, lo = bottom - 0.3)
        assertNull(OrderBlocks.analyze(c)!!.bullishBlock)
    }

    @Test
    fun `equal highs cluster into liquidity`() {
        val c = chop(40)
        val levels = OrderBlocks.liquidityLevels(c, OrderBlocks.swingPoints(c, 2, 2), 1.0, null, null)
        val eqh = levels.filter { it.kind == LiquidityKind.EQH }
        assertTrue(eqh.isNotEmpty() && eqh[0].touches >= 2)
    }

    @Test
    fun `distinct highs do not`() {
        val c = ArrayList<Candle>()
        for (k in 0 until 5) c += chop(8, base = 100.0 + k, t0 = 8 * k)
        val levels = OrderBlocks.liquidityLevels(c, OrderBlocks.swingPoints(c, 2, 2), 1.0, null, null)
        assertTrue(levels.none { it.kind == LiquidityKind.EQH })
    }

    @Test
    fun `a wick above equal highs that closes back is a bearish trap`() {
        val c = chop(20)
        val t = c.size
        c += bar(t, 100.5, 100.4, hi = 101.6, lo = 100.2)
        val traps = OrderBlocks.liquidityTraps(c, listOf(LiquidityLevel(101.0, LiquidityKind.EQH, 2, 10)))
        assertEquals(1, traps.size)
        assertFalse(traps[0].bullish)
        assertEquals(t, traps[0].index)
        assertEquals(101.6, traps[0].extreme, 1e-9)
    }

    @Test
    fun `a close beyond the level is a break, not a trap`() {
        val c = chop(20)
        val t = c.size
        c += bar(t, 100.5, 101.5, hi = 101.7, lo = 100.4)
        c += bar(t + 1, 101.5, 100.5, hi = 101.8, lo = 100.4)
        assertTrue(OrderBlocks.liquidityTraps(c, listOf(LiquidityLevel(101.0, LiquidityKind.EQH, 2, 10))).isEmpty())
    }

    @Test
    fun `too few bars to judge is no analysis`() {
        assertNull(OrderBlocks.analyze(chop(10)))
    }
}

class RestingLiquidityTest {
    private fun bar(t: Int, h: Double, l: Double) = Candle(t.toLong(), (h + l) / 2, h, l, (h + l) / 2, 1.0)

    @Test
    fun `a level a later bar ran is spent, the rest are nearest first`() {
        val candles = listOf(bar(0, 101.0, 99.0), bar(1, 102.5, 100.0), bar(2, 101.0, 99.5))
        val levels = listOf(
            LiquidityLevel(102.0, LiquidityKind.EQH, 2, 0),   // run by bar 1: spent
            LiquidityLevel(103.0, LiquidityKind.EQH, 2, 0),
            LiquidityLevel(104.0, LiquidityKind.EQH, 2, 0),
            LiquidityLevel(105.0, LiquidityKind.EQH, 2, 0),   // third above: dropped
            LiquidityLevel(98.0, LiquidityKind.EQL, 2, 0),
        )
        val resting = OrderBlocks.resting(candles, levels, price = 100.5)
        assertEquals(listOf(104.0, 103.0, 98.0), resting.map { it.price })
    }
}
