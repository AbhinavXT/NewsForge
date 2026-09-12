package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.RankInput
import com.abhinavxt.newsforge.core.rank.Ranker
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** The minimum a freshly stored article needs to expose to be considered for an alert. */
data class AlertCandidate(
    val id: String,
    val clusterId: String,
    val title: String,
    val link: String,
    val sourceName: String,
    val category: Category,
    val tier: SourceTier,
    val publishedAt: Long,
    val symbols: List<String>,
)

/**
 * Two levels, two channels.
 *
 * [HIGH] is for something on your watchlist or a regulator acting — the things worth a
 * sound at 09:20. [NORMAL] is everything else that cleared the bar. Separate channels
 * because Android lets the user silence one without silencing both, and a reader who
 * mutes market-wide noise should not thereby mute their own holdings.
 */
enum class AlertLevel { HIGH, NORMAL }

data class Alert(
    val candidate: AlertCandidate,
    val level: AlertLevel,
    val score: Double,
    /** Strongest tier among the story's symbols, or null if none are followed. */
    val tier: WatchTier? = null,
)

/** User-tunable alert behaviour. */
data class AlertSettings(
    val enabled: Boolean = true,
    /**
     * Bar for a market-wide story.
     *
     * Calibrated against what the ranker actually produces rather than picked by feel.
     * At full recency a wire story scores category weight × 1.2, and a recognised symbol
     * multiplies that by 1.5 — so a bar of 1.8 would pass essentially anything with a
     * ticker in it, and results season alone would fire on every filing. At 2.9 the
     * market-wide bar means: a high-weight category from an official source, or a
     * high-weight category from a wire that also names a company.
     */
    val minScore: Double = 2.9,
    /**
     * Lower bar for something you follow — you asked to hear about these.
     *
     * Low enough that a management change at a company you hold gets through, which the
     * market-wide bar deliberately would not.
     */
    val watchlistMinScore: Double = 1.5,
    /** Nothing older than this, so a backfill never fires alerts about yesterday. */
    val maxAgeMinutes: Long = 180,
    /** Hard ceiling per sync. Results season would otherwise be unusable. */
    val maxPerSync: Int = 4,
    val quietFrom: LocalTime = LocalTime.of(22, 0),
    val quietUntil: LocalTime = LocalTime.of(6, 30),
)

/**
 * Decides which newly stored articles are worth interrupting someone for.
 *
 * The hard part of a news alerter is not finding things to send, it is not sending them.
 * A reader who gets forty notifications during results season turns them off and never
 * turns them back on, at which point the feature is worse than absent — so every rule
 * here is a suppression rule.
 */
object NotificationPolicy {

    /**
     * @param alreadyNotified cluster ids that have already produced an alert. Deduping on
     *   the cluster rather than the article is what stops nine outlets carrying one story
     *   producing nine buzzes.
     * @param firstRun true when the store was empty before this sync. The initial fetch
     *   inserts hundreds of articles at once, and alerting on those would mean installing
     *   the app and immediately being buried.
     */
    fun select(
        candidates: List<AlertCandidate>,
        watchlist: Map<String, WatchTier>,
        alreadyNotified: Set<String>,
        settings: AlertSettings,
        nowMillis: Long,
        firstRun: Boolean,
    ): List<Alert> {
        if (!settings.enabled) return emptyList()
        if (firstRun) return emptyList()
        if (isQuiet(nowMillis, settings)) return emptyList()

        val phase = MarketClock.phase(nowMillis)
        val seen = HashSet(alreadyNotified)
        val chosen = ArrayList<Alert>()

        val ranked = candidates
            .asSequence()
            .filter { ageMinutes(it.publishedAt, nowMillis) <= settings.maxAgeMinutes }
            .map { candidate ->
                val tier = WatchTier.strongest(candidate.symbols, watchlist)
                val score = Ranker.score(
                    RankInput(
                        category = candidate.category,
                        tier = candidate.tier,
                        publishedAtMillis = candidate.publishedAt,
                        symbolCount = candidate.symbols.size,
                        // One article, not a cluster: at insert time we do not yet know
                        // how many outlets will carry it, and waiting to find out would
                        // defeat the point of alerting.
                        clusterSize = 1,
                    ),
                    nowMillis = nowMillis,
                    phase = phase,
                )
                Triple(candidate, score, tier)
            }
            .filter { (_, score, tier) ->
                // A followed symbol is judged at its own tier's bar; everything else has
                // to clear the market-wide one.
                score >= (tier?.alertThreshold ?: settings.minScore)
            }
            .sortedByDescending { it.second }

        for ((candidate, score, tier) in ranked) {
            if (chosen.size >= settings.maxPerSync) break
            if (!seen.add(candidate.clusterId)) continue
            chosen += Alert(
                candidate = candidate,
                level = if (tier != null || candidate.category == Category.REGULATORY) {
                    AlertLevel.HIGH
                } else {
                    AlertLevel.NORMAL
                },
                score = score,
                tier = tier,
            )
        }
        return chosen
    }

    /**
     * Overnight silence, wrap-around aware.
     *
     * Deliberately ends before the pre-open window: news that lands at 06:45 is exactly
     * what the brief is for, and holding it back until 09:00 would waste the one part of
     * the day where a notification is genuinely useful.
     *
     * Leveraged positions do *not* bypass it. There is nothing to be done about a filing
     * at 02:00 that could not equally be done at 06:30, so waking someone would be cost
     * without benefit.
     */
    internal fun isQuiet(nowMillis: Long, settings: AlertSettings): Boolean {
        val time = ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(nowMillis), MarketClock.ZONE)
            .toLocalTime()
        val from = settings.quietFrom
        val until = settings.quietUntil
        return if (from <= until) {
            time >= from && time < until
        } else {
            // Spans midnight.
            time >= from || time < until
        }
    }

    private fun ageMinutes(publishedAt: Long, nowMillis: Long): Long =
        maxOf(0L, (nowMillis - publishedAt) / 60_000)
}
