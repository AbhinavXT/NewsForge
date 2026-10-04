package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceAlertsTest {

    private val now = 1_800_000_000_000L

    private fun quote(symbol: String, ltp: Double, previousClose: Double = 100.0, at: Long? = now) =
        DeskPayload(symbol = symbol, kind = "quote", timestampMillis = at, ltp = ltp, previousClose = previousClose)

    private fun rule(kind: PriceAlertKind, threshold: Double, id: Long = 1, symbol: String = "HAL") =
        PriceAlertRule(id, symbol, kind, threshold)

    @Test
    fun levelsFireOncePastRatherThanOnlyOnTheCrossingTick() {
        val quotes = mapOf("HAL" to quote("HAL", 105.0))

        val hits = PriceAlerts.check(
            listOf(
                rule(PriceAlertKind.ABOVE, 104.0, id = 1),
                rule(PriceAlertKind.ABOVE, 106.0, id = 2),
                rule(PriceAlertKind.BELOW, 105.0, id = 3),
                rule(PriceAlertKind.BELOW, 99.0, id = 4),
            ),
            quotes,
            now,
        )

        assertEquals(listOf(1L, 3L), hits.map { it.rule.id })
        assertEquals(105.0, hits.first().price, 0.0)
    }

    @Test
    fun aMoveAlertFiresEitherWay() {
        val rules = listOf(rule(PriceAlertKind.MOVE, 3.0))

        assertEquals(1, PriceAlerts.check(rules, mapOf("HAL" to quote("HAL", 96.5)), now).size)
        assertEquals(1, PriceAlerts.check(rules, mapOf("HAL" to quote("HAL", 103.0)), now).size)
        assertEquals(0, PriceAlerts.check(rules, mapOf("HAL" to quote("HAL", 102.9)), now).size)
    }

    @Test
    fun staleOrMissingQuotesFireNothing() {
        val rules = listOf(rule(PriceAlertKind.ABOVE, 50.0))
        val stale = now - PriceAlerts.MAX_QUOTE_AGE_MS - 1

        assertEquals(0, PriceAlerts.check(rules, mapOf("HAL" to quote("HAL", 105.0, at = stale)), now).size)
        assertEquals(0, PriceAlerts.check(rules, mapOf("BEL" to quote("BEL", 105.0)), now).size)
        assertEquals(1, PriceAlerts.check(rules, mapOf("HAL" to quote("HAL", 105.0, at = null)), now).size)
    }

    @Test
    fun wordingNamesTheEventAndTheRule() {
        val hit = PriceAlertHit(rule(PriceAlertKind.ABOVE, 4500.0), price = 4512.3, changePercent = 2.14)

        assertEquals("HAL is above ₹4,500", PriceAlerts.headline(hit))
        assertEquals(
            "Now ₹4,512 · +2.1% today · you asked for: rises above ₹4,500",
            PriceAlerts.detail(hit),
        )
        assertEquals(
            "HAL is down 3.2% today",
            PriceAlerts.headline(PriceAlertHit(rule(PriceAlertKind.MOVE, 3.0), 96.8, -3.2)),
        )
    }
}
