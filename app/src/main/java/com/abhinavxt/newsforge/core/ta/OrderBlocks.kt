package com.abhinavxt.newsforge.core.ta

import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.core.rank.MarketClock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Order blocks and liquidity, from price alone — TickerForge's `board.py`, ported.
 *
 * Same rules and the same constants, so a zone drawn here is the zone the desk's board
 * ranks on: a *displacement* is a bar whose body is more than [K_ATR] ATRs and closes
 * through the last confirmed swing; its *order block* is the last opposite-colour bar
 * before it, live until price trades back through it. *Liquidity* rests at equal highs and
 * lows and at the previous session's extremes; a *trap* is a bar that runs one of those
 * levels and closes back inside.
 *
 * What the desk adds and this cannot is futures open interest, which is why the score is
 * not ported: without OI a third of it would be guessed. The board carries the score; this
 * draws the zones on whatever bars the chart has.
 */
object OrderBlocks {

    const val K_ATR = 1.5
    const val ATR_LEN = 14
    const val SWING_LEFT = 2
    const val SWING_RIGHT = 2
    const val OB_LOOKBACK = 10
    const val EQ_TOL_ATR = 0.15
    /** A sweep this many bars old still counts as recent. */
    const val TRAP_RECENT = 12
    /** "At the zone": within this many ATRs of the block. */
    const val REVERSAL_NEAR_ATR = 0.5

    fun analyze(candles: List<Candle>, ltp: Double? = null): ZoneAnalysis? {
        if (candles.size < ATR_LEN + SWING_RIGHT + 2) return null
        val price = ltp ?: candles.last().close
        val atrs = atr(candles, ATR_LEN)
        val atrNow = atrs.lastOrNull { it != null }
        val swings = swingPoints(candles, SWING_LEFT, SWING_RIGHT)
        val displacements = findDisplacements(candles, atrs, swings)

        val bull = latestUnmitigated(candles, displacements, bullish = true)
        val bear = latestUnmitigated(candles, displacements, bullish = false)

        val (pdh, pdl) = previousSession(candles)
        val liquidity = liquidityLevels(candles, swings, atrNow, pdh, pdl)
        val traps = liquidityTraps(candles, liquidity)
        val recentTrap = traps.firstOrNull { it.index >= candles.size - TRAP_RECENT }

        // The block nearer the price is the one in play; a tie goes to the bar's direction.
        val nearest = listOfNotNull(bull, bear).minByOrNull { distance(price, it) }
        val distanceAtr = nearest?.let { ob -> atrNow?.takeIf { it > 0 }?.let { distance(price, ob) / it } }
        val reversal = nearest != null && distanceAtr != null && distanceAtr <= REVERSAL_NEAR_ATR &&
            traps.any { it.bullish == nearest.bullish && it.index >= candles.size - TRAP_RECENT }

        val highs = swings.highs.map { candles[it].high }
        val lows = swings.lows.map { candles[it].low }
        return ZoneAnalysis(
            bullishBlock = bull,
            bearishBlock = bear,
            active = nearest,
            distanceAtr = distanceAtr,
            distancePercent = nearest?.let { ob ->
                if (price in ob.bottom..ob.top) 0.0
                else ((if (price > ob.top) ob.top else ob.bottom) - price) / price * 100
            },
            resistance = highs.filter { it > price }.minOrNull(),
            support = lows.filter { it < price }.maxOrNull(),
            liquidity = liquidity,
            resting = resting(candles, liquidity, price),
            traps = traps,
            recentTrap = recentTrap,
            reversal = reversal,
            atr = atrNow,
        )
    }

    // ------------------------------------------------------------------ building blocks

    /** Wilder's ATR, aligned to the bars; null until there are [period] of them. */
    fun atr(candles: List<Candle>, period: Int): List<Double?> {
        val out = arrayOfNulls<Double>(candles.size).toMutableList()
        if (candles.size < period) return out
        val tr = candles.mapIndexed { i, c ->
            if (i == 0) c.high - c.low
            else maxOf(c.high - c.low, abs(c.high - candles[i - 1].close), abs(c.low - candles[i - 1].close))
        }
        var prev = tr.take(period).average()
        out[period - 1] = prev
        for (i in period until tr.size) {
            prev = (prev * (period - 1) + tr[i]) / period
            out[i] = prev
        }
        return out
    }

    data class Swings(val highs: List<Int>, val lows: List<Int>)

    /**
     * Confirmed pivots: higher than the [left] bars before and strictly higher than the
     * [right] after (lows mirrored). The newest [right] bars cannot be pivots yet.
     */
    fun swingPoints(candles: List<Candle>, left: Int, right: Int): Swings {
        val highs = ArrayList<Int>()
        val lows = ArrayList<Int>()
        for (i in left until candles.size - right) {
            val h = candles[i].high
            val l = candles[i].low
            if ((i - left until i).all { h >= candles[it].high } &&
                (i + 1..i + right).all { h > candles[it].high }
            ) highs += i
            if ((i - left until i).all { l <= candles[it].low } &&
                (i + 1..i + right).all { l < candles[it].low }
            ) lows += i
        }
        return Swings(highs, lows)
    }

    data class Displacement(val index: Int, val bullish: Boolean, val atrMultiple: Double)

    fun findDisplacements(candles: List<Candle>, atrs: List<Double?>, swings: Swings): List<Displacement> {
        val out = ArrayList<Displacement>()
        for ((i, c) in candles.withIndex()) {
            val a = atrs.getOrNull(i)?.takeIf { it > 0 } ?: continue
            val body = abs(c.close - c.open)
            if (body <= K_ATR * a) continue
            if (c.close > c.open) {
                // The swing must already be confirmed by this bar, not by later ones.
                val level = swings.highs.lastOrNull { it + SWING_RIGHT < i }?.let { candles[it].high } ?: continue
                if (c.close > level) out += Displacement(i, true, body / a)
            } else if (c.close < c.open) {
                val level = swings.lows.lastOrNull { it + SWING_RIGHT < i }?.let { candles[it].low } ?: continue
                if (c.close < level) out += Displacement(i, false, body / a)
            }
        }
        return out
    }

    private fun latestUnmitigated(
        candles: List<Candle>,
        displacements: List<Displacement>,
        bullish: Boolean,
    ): OrderBlock? {
        for (d in displacements.asReversed()) {
            if (d.bullish != bullish) continue
            val ob = blockFor(candles, d) ?: continue
            if (!mitigated(candles, ob)) return ob
        }
        return null
    }

    /** The last opposite-colour bar before a displacement; the zone is its full range. */
    private fun blockFor(candles: List<Candle>, d: Displacement): OrderBlock? {
        for (j in d.index - 1 downTo maxOf(0, d.index - OB_LOOKBACK)) {
            val c = candles[j]
            val opposite = if (d.bullish) c.close < c.open else c.close > c.open
            if (opposite) {
                return OrderBlock(
                    bullish = d.bullish,
                    top = c.high,
                    bottom = c.low,
                    index = j,
                    displacementIndex = d.index,
                    openTimeMillis = c.openTimeMillis,
                    moveAtr = d.atrMultiple,
                )
            }
        }
        return null
    }

    /** Dead once price trades back through it; a touch — the retest — is the trade. */
    private fun mitigated(candles: List<Candle>, ob: OrderBlock): Boolean {
        val after = candles.subList((ob.displacementIndex + 1).coerceAtMost(candles.size), candles.size)
        return if (ob.bullish) after.any { it.low < ob.bottom } else after.any { it.high > ob.top }
    }

    /**
     * The previous session's high and low, when the bars are intraday and span more than
     * one session. On daily bars "the previous session" is just the bar before, which is
     * already a swing candidate, so nothing is added.
     */
    private fun previousSession(candles: List<Candle>): Pair<Double?, Double?> {
        if (candles.size < 2) return null to null
        val days = candles.map { dayOf(it.openTimeMillis) }
        if (days.distinct().size == days.size) return null to null
        val today = days.last()
        val prior = days.lastOrNull { it < today } ?: return null to null
        val bars = candles.filterIndexed { i, _ -> days[i] == prior }
        return bars.maxOf { it.high } to bars.minOf { it.low }
    }

    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(MarketClock.ZONE).toLocalDate()

    private fun sessionStart(candles: List<Candle>): Int {
        val last = dayOf(candles.last().openTimeMillis)
        var i = candles.lastIndex
        while (i > 0 && dayOf(candles[i - 1].openTimeMillis) == last) i--
        return i
    }

    /**
     * Equal highs and lows — two or more swings within [EQ_TOL_ATR] ATRs — plus the
     * previous session's extremes. A level is known from the bar that confirmed its last
     * swing, so a sweep is only looked for after that.
     */
    fun liquidityLevels(
        candles: List<Candle>,
        swings: Swings,
        atrNow: Double?,
        pdh: Double?,
        pdl: Double?,
    ): List<LiquidityLevel> {
        val out = ArrayList<LiquidityLevel>()
        if (atrNow != null && atrNow > 0) {
            val tol = EQ_TOL_ATR * atrNow
            for ((indices, kind) in listOf(swings.highs to LiquidityKind.EQH, swings.lows to LiquidityKind.EQL)) {
                val pairs = indices.map { (if (kind == LiquidityKind.EQH) candles[it].high else candles[it].low) to it }
                for (group in cluster(pairs, tol)) {
                    if (group.size < 2) continue
                    out += LiquidityLevel(
                        price = if (kind == LiquidityKind.EQH) group.maxOf { it.first } else group.minOf { it.first },
                        kind = kind,
                        touches = group.size,
                        formedIndex = group.maxOf { it.second } + SWING_RIGHT,
                    )
                }
            }
        }
        val start = sessionStart(candles)
        pdh?.let { out += LiquidityLevel(it, LiquidityKind.PDH, 1, start) }
        pdl?.let { out += LiquidityLevel(it, LiquidityKind.PDL, 1, start) }
        return out.sortedBy { it.price }
    }

    private fun cluster(pairs: List<Pair<Double, Int>>, tol: Double): List<List<Pair<Double, Int>>> {
        val groups = ArrayList<MutableList<Pair<Double, Int>>>()
        for (p in pairs.sortedWith(compareBy({ it.first }, { it.second }))) {
            val last = groups.lastOrNull()
            if (last != null && p.first - last.last().first <= tol) last += p else groups += mutableListOf(p)
        }
        return groups
    }

    /**
     * Sweeps: the first bar after a level formed that runs it. Closing back inside is a
     * trap; closing beyond means the level broke, and either way its liquidity is spent.
     * Newest first.
     */
    fun liquidityTraps(candles: List<Candle>, levels: List<LiquidityLevel>): List<LiquidityTrap> {
        val out = ArrayList<LiquidityTrap>()
        for (level in levels) {
            val above = level.kind == LiquidityKind.EQH || level.kind == LiquidityKind.PDH
            for (i in level.formedIndex.coerceAtLeast(0) until candles.size) {
                val c = candles[i]
                val ran = if (above) c.high > level.price else c.low < level.price
                if (!ran) continue
                val back = if (above) c.close < level.price else c.close > level.price
                if (back) {
                    out += LiquidityTrap(
                        index = i,
                        openTimeMillis = c.openTimeMillis,
                        price = level.price,
                        kind = level.kind,
                        bullish = !above,
                        extreme = if (above) c.high else c.low,
                    )
                }
                break
            }
        }
        return out.sortedByDescending { it.index }
    }

    /** Levels each side of the price worth drawing; the rest are on the record, not the chart. */
    const val RESTING_PER_SIDE = 2

    /**
     * Liquidity still resting: levels no bar has run since they formed, nearest the price
     * first, [RESTING_PER_SIDE] above and below. A swept or broken level's orders have
     * been filled either way, and a year of daily bars leaves dozens of those — drawing
     * them all buries the two that matter.
     */
    fun resting(candles: List<Candle>, levels: List<LiquidityLevel>, price: Double): List<LiquidityLevel> {
        val live = levels.filter { level ->
            val above = level.kind == LiquidityKind.EQH || level.kind == LiquidityKind.PDH
            (level.formedIndex.coerceAtLeast(0) until candles.size).none { i ->
                if (above) candles[i].high > level.price else candles[i].low < level.price
            }
        }
        val up = live.filter { it.price >= price }.sortedBy { it.price }.take(RESTING_PER_SIDE)
        val down = live.filter { it.price < price }.sortedByDescending { it.price }.take(RESTING_PER_SIDE)
        return (up + down).sortedByDescending { it.price }
    }

    private fun distance(price: Double, ob: OrderBlock): Double = when {
        price > ob.top -> price - ob.top
        price < ob.bottom -> ob.bottom - price
        else -> 0.0
    }
}

data class OrderBlock(
    val bullish: Boolean,
    val top: Double,
    val bottom: Double,
    /** The block bar's index in the analysed candles. */
    val index: Int,
    val displacementIndex: Int,
    val openTimeMillis: Long,
    /** How hard the move that left it was, in ATRs. */
    val moveAtr: Double,
)

enum class LiquidityKind { EQH, EQL, PDH, PDL }

data class LiquidityLevel(
    val price: Double,
    val kind: LiquidityKind,
    val touches: Int,
    val formedIndex: Int,
)

data class LiquidityTrap(
    val index: Int,
    val openTimeMillis: Long,
    val price: Double,
    val kind: LiquidityKind,
    /** The direction it points: a sweep below a low traps sellers and points up. */
    val bullish: Boolean,
    val extreme: Double,
)

data class ZoneAnalysis(
    val bullishBlock: OrderBlock?,
    val bearishBlock: OrderBlock?,
    /** Whichever live block is nearer the price — the one in play. */
    val active: OrderBlock?,
    val distanceAtr: Double?,
    /** Signed % from price to the active block's nearer edge; 0 inside it. */
    val distancePercent: Double?,
    val resistance: Double?,
    val support: Double?,
    /** Every level found, swept or not. */
    val liquidity: List<LiquidityLevel>,
    /** Unswept levels nearest the price; see [OrderBlocks.resting]. What the chart draws. */
    val resting: List<LiquidityLevel>,
    val traps: List<LiquidityTrap>,
    val recentTrap: LiquidityTrap?,
    /** Price at the active block with a recent sweep pointing the same way. */
    val reversal: Boolean,
    val atr: Double?,
)
