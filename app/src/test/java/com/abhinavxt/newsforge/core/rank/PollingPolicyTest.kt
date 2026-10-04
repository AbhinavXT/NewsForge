package com.abhinavxt.newsforge.core.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class PollingPolicyTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    @Test
    fun theSessionPollsFastest() {
        val open = PollingPolicy.intervalMillisFor(MarketPhase.OPEN)
        assertNotNull(open)
        assertTrue(open!! < PollingPolicy.intervalMillisFor(MarketPhase.PRE_OPEN)!!)
        assertTrue(open < PollingPolicy.intervalMillisFor(MarketPhase.POST_CLOSE)!!)
    }

    @Test
    fun preOpenPollsFasterThanAfterTheClose() {
        // The pre-open window is when the gap list is built, so it earns a tighter loop
        // than an evening where filings trickle in.
        assertTrue(
            PollingPolicy.intervalMillisFor(MarketPhase.PRE_OPEN)!! <
                PollingPolicy.intervalMillisFor(MarketPhase.POST_CLOSE)!!
        )
    }

    @Test
    fun weekendsDoNotPollInTheForeground() {
        assertNull(PollingPolicy.intervalMillisFor(MarketPhase.WEEKEND))
    }

    @Test
    fun everyIntervalIsAtLeastThirtySeconds() {
        // Roughly twenty feeds per cycle: anything tighter is impolite even with
        // conditional requests doing most of the work.
        for (phase in MarketPhase.entries) {
            PollingPolicy.intervalMillisFor(phase)?.let {
                assertTrue("$phase polls every ${it}ms", it >= 30_000L)
            }
        }
    }

    @Test
    fun theClockPicksTheCadence() {
        // 2026-09-09 is a Wednesday; 2026-09-12 a Saturday.
        assertEquals(
            PollingPolicy.intervalMillisFor(MarketPhase.OPEN),
            PollingPolicy.intervalMillisAt(ist("2026-09-09T11:00:00")),
        )
        assertEquals(
            PollingPolicy.intervalMillisFor(MarketPhase.PRE_OPEN),
            PollingPolicy.intervalMillisAt(ist("2026-09-09T08:00:00")),
        )
        assertNull(PollingPolicy.intervalMillisAt(ist("2026-09-12T11:00:00")))
    }
}
