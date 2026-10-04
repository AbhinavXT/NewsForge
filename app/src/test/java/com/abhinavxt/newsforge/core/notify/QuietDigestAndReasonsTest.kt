package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuietDigestAndReasonsTest {

    private val settings = AlertSettings(quietFrom = LocalTime.of(22, 0), quietUntil = LocalTime.of(6, 30))

    private fun at(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(MarketClock.ZONE).toInstant().toEpochMilli()

    private fun candidate(
        id: String,
        category: Category,
        symbols: List<String> = emptyList(),
        publishedAt: Long,
        tier: SourceTier = SourceTier.OFFICIAL,
    ) = AlertCandidate(id, id, "Story $id", "https://x/$id", "NSE", category, tier, publishedAt, symbols)

    @Test
    fun theQuietWindowIsFoundAcrossMidnight() {
        val morning = at("2026-10-05", "07:10")

        val end = NotificationPolicy.lastQuietEnd(morning, settings)!!

        assertEquals(at("2026-10-05", "06:30"), end)
        assertEquals(at("2026-10-04", "22:00"), NotificationPolicy.quietStart(end, settings))
        // Still quiet: nothing has ended yet.
        assertNull(NotificationPolicy.lastQuietEnd(at("2026-10-05", "05:00"), settings))
        // Off when the two times are equal.
        assertNull(
            NotificationPolicy.lastQuietEnd(morning, settings.copy(quietFrom = LocalTime.NOON, quietUntil = LocalTime.NOON))
        )
    }

    @Test
    fun theDigestKeepsWhatTheNightHeldBackRegardlessOfAge() {
        val night = at("2026-10-05", "01:00")
        val stories = listOf(
            candidate("a", Category.REGULATORY, publishedAt = night),
            candidate("b", Category.RESULTS, listOf("HAL"), publishedAt = night),
            candidate("c", Category.OTHER, publishedAt = night, tier = SourceTier.AGGREGATOR),
        )

        val digest = NotificationPolicy.digest(
            candidates = stories,
            watchlist = mapOf("HAL" to WatchTier.HOLDING),
            alreadyNotified = emptySet(),
            settings = settings,
            // Seven hours after publication: far past the live alert age limit.
            nowMillis = at("2026-10-05", "08:00"),
        )

        assertEquals(listOf("b", "a"), digest.map { it.candidate.id })
        assertEquals("HAL", digest.first().subject)
    }

    @Test
    fun reasonsNameTheRuleThatFired() {
        val now = at("2026-10-05", "10:00")
        val held = Alert(
            candidate(id = "b", category = Category.RESULTS, symbols = listOf("HAL"), publishedAt = now),
            AlertLevel.HIGH, 3.0, tier = WatchTier.HOLDING, subject = "HAL",
        )
        val every = held.copy(everyStory = true)
        val regulatory = Alert(candidate("a", Category.REGULATORY, publishedAt = now), AlertLevel.HIGH, 3.0)
        val market = Alert(
            candidate("m", Category.MERGER, publishedAt = now, tier = SourceTier.OFFICIAL),
            AlertLevel.NORMAL,
            3.0,
        )

        assertEquals("Holding: HAL", AlertReasons.short(held))
        assertEquals("You hold HAL, and this results story cleared the holding bar.", AlertReasons.long(held))
        assertEquals("Every story on HAL", AlertReasons.short(every))
        assertEquals("Regulatory action", AlertReasons.short(regulatory))
        assertEquals("Market-wide m&a", AlertReasons.short(market))
        assertTrue(AlertReasons.long(market).contains("an official source"))
    }
}
