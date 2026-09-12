package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class NotificationPolicyTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    /** 2026-09-09 is a Wednesday; 10:30 is mid-session. */
    private val now = ist("2026-09-09T10:30:00")
    private val minute = 60_000L

    private fun candidate(
        id: String,
        category: Category = Category.RESULTS,
        tier: SourceTier = SourceTier.OFFICIAL,
        ageMinutes: Long = 2,
        symbols: List<String> = emptyList(),
        clusterId: String = id,
    ) = AlertCandidate(
        id = id,
        clusterId = clusterId,
        title = id,
        link = "https://example.com/$id",
        sourceName = "Wire",
        category = category,
        tier = tier,
        publishedAt = now - ageMinutes * minute,
        symbols = symbols,
    )

    private fun select(
        candidates: List<AlertCandidate>,
        watchlist: Map<String, WatchTier> = emptyMap(),
        alreadyNotified: Set<String> = emptySet(),
        settings: AlertSettings = AlertSettings(),
        nowMillis: Long = now,
        firstRun: Boolean = false,
    ) = NotificationPolicy.select(candidates, watchlist, alreadyNotified, settings, nowMillis, firstRun)

    @Test
    fun highValueStoriesClearTheBar() {
        val alerts = select(listOf(candidate("sebi", Category.REGULATORY)))
        assertEquals(listOf("sebi"), alerts.map { it.candidate.id })
        assertEquals(AlertLevel.HIGH, alerts.single().level)
    }

    @Test
    fun ordinaryStoriesDoNot() {
        assertTrue(select(listOf(candidate("filler", Category.OTHER))).isEmpty())
    }

    @Test
    fun aRoutineResultsFilingFromAWireDoesNotAlert() {
        // Results season is the stress case: hundreds of filings a day, every one of them
        // naming a company. If these get through, the feature gets switched off.
        val routine = candidate("q2", Category.RESULTS, SourceTier.WIRE, symbols = listOf("SBIN"))
        assertTrue(select(listOf(routine)).isEmpty())
    }

    @Test
    fun eachTierHasItsOwnBar() {
        // Same filing, three levels of exposure. A shrug when you are tracking the name;
        // something you may have to act on at the open when you are on margin.
        val story = candidate("mine", Category.OTHER, SourceTier.WIRE, symbols = listOf("INFY"))
        assertTrue(select(listOf(story)).isEmpty())
        assertTrue(select(listOf(story), mapOf("INFY" to WatchTier.WATCHING)).isEmpty())
        assertTrue(select(listOf(story), mapOf("INFY" to WatchTier.HOLDING)).isEmpty())
        assertEquals(1, select(listOf(story), mapOf("INFY" to WatchTier.LEVERAGED)).size)
    }

    @Test
    fun thresholdsDescendWithExposure() {
        assertTrue(WatchTier.WATCHING.alertThreshold > WatchTier.HOLDING.alertThreshold)
        assertTrue(WatchTier.HOLDING.alertThreshold > WatchTier.LEVERAGED.alertThreshold)
    }

    @Test
    fun aStoryIsJudgedAtItsStrongestSymbolsTier() {
        val story = candidate(
            "pair", Category.OTHER, SourceTier.WIRE, symbols = listOf("INFY", "TCS")
        )
        val tiers = mapOf("INFY" to WatchTier.WATCHING, "TCS" to WatchTier.LEVERAGED)
        assertEquals(1, select(listOf(story), tiers).size)
        assertEquals(WatchTier.LEVERAGED, select(listOf(story), tiers).single().tier)
    }

    @Test
    fun nothingAtNightEvenForALeveragedPosition() {
        // There is nothing to be done at 02:00 that could not be done at 06:30.
        assertTrue(
            select(
                listOf(candidate("mine", Category.REGULATORY, symbols = listOf("INFY"))),
                watchlist = mapOf("INFY" to WatchTier.LEVERAGED),
                nowMillis = ist("2026-09-09T02:00:00"),
            ).isEmpty()
        )
    }

    @Test
    fun watchlistStoriesClearALowerBar() {
        val story = candidate("mine", Category.MANAGEMENT, SourceTier.WIRE, symbols = listOf("INFY"))
        // Below the market-wide bar on its own merits.
        assertTrue(select(listOf(story)).isEmpty())
        val alerts = select(listOf(story), watchlist = mapOf("INFY" to WatchTier.WATCHING))
        assertEquals(listOf("mine"), alerts.map { it.candidate.id })
        assertEquals(AlertLevel.HIGH, alerts.single().level)
    }

    @Test
    fun firstRunNeverAlerts() {
        // The initial fetch inserts hundreds of articles; alerting on those would mean
        // installing the app and immediately being buried.
        val many = (1..20).map { candidate("s$it", Category.REGULATORY) }
        assertTrue(select(many, firstRun = true).isEmpty())
        assertFalse(select(many, firstRun = false).isEmpty())
    }

    @Test
    fun oneStoryCarriedByNineOutletsBuzzesOnce() {
        val shared = (1..9).map {
            candidate("outlet$it", Category.REGULATORY, clusterId = "story")
        }
        assertEquals(1, select(shared).size)
    }

    @Test
    fun aClusterAlreadyNotifiedIsNotRepeated() {
        val story = candidate("late", Category.REGULATORY, clusterId = "story")
        assertTrue(select(listOf(story), alreadyNotified = setOf("story")).isEmpty())
    }

    @Test
    fun staleArticlesAreIgnored() {
        // A feed that suddenly starts serving a week of history must not fire alerts.
        val old = candidate("backfill", Category.REGULATORY, ageMinutes = 60 * 24)
        assertTrue(select(listOf(old)).isEmpty())
    }

    @Test
    fun theBatchIsCappedAndKeepsTheStrongestStories() {
        val candidates = listOf(
            candidate("regulatory", Category.REGULATORY, symbols = listOf("TATASTEEL")),
            candidate("merger", Category.MERGER, symbols = listOf("INFY")),
            candidate("results", Category.RESULTS, symbols = listOf("SBIN")),
        )
        val alerts = select(candidates, settings = AlertSettings(maxPerSync = 2))
        assertEquals(2, alerts.size)
        assertTrue(alerts[0].score >= alerts[1].score)
        assertEquals(listOf("regulatory", "merger"), alerts.map { it.candidate.id })
    }

    @Test
    fun disablingSuppressesEverything() {
        assertTrue(
            select(
                listOf(candidate("sebi", Category.REGULATORY)),
                settings = AlertSettings(enabled = false),
            ).isEmpty()
        )
    }

    @Test
    fun quietHoursWrapAroundMidnight() {
        val settings = AlertSettings()
        assertTrue(NotificationPolicy.isQuiet(ist("2026-09-09T23:30:00"), settings))
        assertTrue(NotificationPolicy.isQuiet(ist("2026-09-09T02:00:00"), settings))
        assertFalse(NotificationPolicy.isQuiet(ist("2026-09-09T10:30:00"), settings))
    }

    @Test
    fun quietHoursEndBeforeThePreOpenWindow() {
        // 06:45 news is exactly what the brief is for; holding it until 09:00 would waste
        // the most useful notification of the day.
        assertFalse(NotificationPolicy.isQuiet(ist("2026-09-09T06:45:00"), AlertSettings()))
        assertFalse(
            select(
                listOf(candidate("premarket", Category.REGULATORY)),
                nowMillis = ist("2026-09-09T06:45:00"),
            ).isEmpty()
        )
    }

    @Test
    fun nothingAtNightEvenForTheWatchlist() {
        assertTrue(
            select(
                listOf(candidate("mine", Category.REGULATORY, symbols = listOf("INFY"))),
                watchlist = mapOf("INFY" to WatchTier.WATCHING),
                nowMillis = ist("2026-09-09T23:30:00"),
            ).isEmpty()
        )
    }

    @Test
    fun emptyInputProducesNoAlerts() {
        assertTrue(select(emptyList()).isEmpty())
    }
}
