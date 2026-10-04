package com.abhinavxt.newsforge.core.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class MarketClockTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    @Test
    fun classifiesWeekdayPhases() {
        // 2026-09-09 is a Wednesday.
        assertEquals(MarketPhase.PRE_OPEN, MarketClock.phase(ist("2026-09-09T08:30:00")))
        assertEquals(MarketPhase.PRE_OPEN, MarketClock.phase(ist("2026-09-09T09:14:59")))
        assertEquals(MarketPhase.OPEN, MarketClock.phase(ist("2026-09-09T09:15:00")))
        assertEquals(MarketPhase.OPEN, MarketClock.phase(ist("2026-09-09T15:29:59")))
        assertEquals(MarketPhase.POST_CLOSE, MarketClock.phase(ist("2026-09-09T15:30:00")))
        assertEquals(MarketPhase.POST_CLOSE, MarketClock.phase(ist("2026-09-09T23:59:00")))
    }

    @Test
    fun classifiesWeekend() {
        // 2026-09-12 is a Saturday, 2026-09-13 a Sunday.
        assertEquals(MarketPhase.WEEKEND, MarketClock.phase(ist("2026-09-12T11:00:00")))
        assertEquals(MarketPhase.WEEKEND, MarketClock.phase(ist("2026-09-13T11:00:00")))
    }

    @Test
    fun overnightDecaysSlowerThanTheSession() {
        assertTrue(
            MarketClock.halfLifeMinutes(MarketPhase.PRE_OPEN) >
                MarketClock.halfLifeMinutes(MarketPhase.OPEN)
        )
        assertTrue(
            MarketClock.halfLifeMinutes(MarketPhase.WEEKEND) >
                MarketClock.halfLifeMinutes(MarketPhase.POST_CLOSE)
        )
    }

    @Test
    fun lastCloseIsTodayOnceTheSessionHasEnded() {
        assertEquals(
            ist("2026-09-09T15:30:00"),
            MarketClock.lastCloseMillis(ist("2026-09-09T18:00:00")),
        )
    }

    @Test
    fun lastCloseIsYesterdayDuringTheMorning() {
        assertEquals(
            ist("2026-09-08T15:30:00"),
            MarketClock.lastCloseMillis(ist("2026-09-09T08:00:00")),
        )
    }

    @Test
    fun lastCloseSkipsTheWeekend() {
        // Monday morning must look back to Friday's close, not Sunday's.
        assertEquals(
            ist("2026-09-11T15:30:00"),
            MarketClock.lastCloseMillis(ist("2026-09-14T08:00:00")),
        )
    }
}
