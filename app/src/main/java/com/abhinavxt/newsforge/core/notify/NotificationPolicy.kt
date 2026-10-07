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
    /** For keyword matching on world stories; the market policy reads headlines only. */
    val summary: String? = null,
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
    /**
     * Whether any of the story's symbols is on the watchlist at all, silenced or not.
     *
     * Not the same as [tier] being set: a silenced company has no tier here, but offering
     * to Watch it would overwrite the tier you gave it with the default.
     */
    val followed: Boolean = tier != null,
    /** The followed company that put the story over its bar, for saying why it rang. */
    val subject: String? = null,
    /** True when [subject] is set to hear about every story, whatever it scores. */
    val everyStory: Boolean = false,
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
    /**
     * @param weights portfolio share per symbol, where known. Shifts the tier a story is
     *   judged at — see [PositionWeight] — so a large position is heard from sooner and a
     *   token one stops contributing most of the notifications you learn to ignore.
     * @param levels per-company alert levels, where set; see [CompanyAlertLevel]. A
     *   silenced company counts as not followed, and one set to [CompanyAlertLevel.ALL]
     *   alerts on any fresh story, whatever it scores. Quiet hours, the age limit, dedupe
     *   and mutes still apply to both.
     */
    fun select(
        candidates: List<AlertCandidate>,
        watchlist: Map<String, WatchTier>,
        alreadyNotified: Set<String>,
        settings: AlertSettings,
        nowMillis: Long,
        firstRun: Boolean,
        // Last, and defaulted, so every existing positional caller keeps working. A
        // parameter added in the middle of a list this long is a silent argument shuffle
        // in anything that does not name them.
        weights: Map<String, Double> = emptyMap(),
        levels: Map<String, CompanyAlertLevel> = emptyMap(),
    ): List<Alert> {
        if (!settings.enabled) return emptyList()
        if (firstRun) return emptyList()
        if (isQuiet(nowMillis, settings)) return emptyList()

        val phase = MarketClock.phase(nowMillis)
        val audible = CompanyAlertLevel.audible(watchlist, levels)
        val seen = HashSet(alreadyNotified)
        val chosen = ArrayList<Alert>()

        val ranked = candidates
            .asSequence()
            .filter { ageMinutes(it.publishedAt, nowMillis) <= settings.maxAgeMinutes }
            .map { candidate ->
                val tier = WatchTier.strongest(candidate.symbols, audible)?.let { held ->
                    PositionWeight.effectiveTier(
                        held,
                        PositionWeight.strongestWeight(candidate.symbols, audible, weights),
                    )
                }
                // The loudest of the story's audible companies wins: a results story
                // naming one core holding and three tracked peers is about the holding.
                val everyStory = candidate.symbols.any {
                    it in audible && levels[it] == CompanyAlertLevel.ALL
                }
                val score = Ranker.score(
                    RankInput(
                        category = candidate.category,
                        tier = candidate.tier,
                        publishedAtMillis = candidate.publishedAt,
                        symbolCount = candidate.symbols.size,
                        // One article, not a cluster: at insert time we do not yet know
                        // how many outlets will carry it, and waiting to find out would
                        // defeat the point of alerting.
                        // One, always: an alert candidate is a row that was just
                        // inserted, and whatever else lands in its cluster has not
                        // arrived yet. Scoring it as though it had been picked up would
                        // be guessing at the future to decide whether to wake someone.
                        outletCount = 1,
                    ),
                    nowMillis = nowMillis,
                    phase = phase,
                )
                // The name to credit in "why": the strongest followed one on the story.
                val subject = candidate.symbols
                    .filter { it in audible }
                    .maxByOrNull { audible.getValue(it).order }
                Scored(candidate, score, tier, everyStory, subject)
            }
            .filter { scored ->
                // A followed symbol is judged at its own tier's bar; everything else has
                // to clear the market-wide one.
                val bar = when {
                    scored.tier == null -> settings.minScore
                    scored.everyStory -> 0.0
                    else -> scored.tier.alertThreshold
                }
                scored.score >= bar
            }
            // Followed names first, then by score. The per-sync cap is shared, and on a
            // busy morning market-wide stories could otherwise fill it and push out the
            // one about a company you hold — the alert the feature exists for.
            .sortedWith(
                compareByDescending<Scored> { it.tier != null }.thenByDescending { it.score }
            )

        for ((candidate, score, tier, everyStory, subject) in ranked) {
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
                followed = tier != null || candidate.symbols.any { it in watchlist },
                subject = subject,
                everyStory = everyStory,
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
    fun isQuiet(nowMillis: Long, settings: AlertSettings): Boolean {
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

    private data class Scored(
        val candidate: AlertCandidate,
        val score: Double,
        val tier: WatchTier?,
        val everyStory: Boolean,
        val subject: String?,
    )

    /** Quiet hours are off when they start and end at the same minute. */
    fun quietHoursOn(settings: AlertSettings): Boolean = settings.quietFrom != settings.quietUntil

    /**
     * When the most recent quiet period ended, at or before [nowMillis].
     *
     * Null while it is still quiet, and when quiet hours are off — there is then no
     * period for a digest to summarise.
     */
    fun lastQuietEnd(nowMillis: Long, settings: AlertSettings): Long? {
        if (!quietHoursOn(settings) || isQuiet(nowMillis, settings)) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), MarketClock.ZONE)
        var end = now.toLocalDate().atTime(settings.quietUntil).atZone(MarketClock.ZONE)
        if (end.isAfter(now)) end = end.minusDays(1)
        return end.toInstant().toEpochMilli()
    }

    /** When the quiet period ending at [endMillis] began. */
    fun quietStart(endMillis: Long, settings: AlertSettings): Long {
        val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endMillis), MarketClock.ZONE)
        var start = end.toLocalDate().atTime(settings.quietFrom).atZone(MarketClock.ZONE)
        if (!start.isBefore(end)) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }

    /**
     * What was held back by quiet hours, best first, for one digest in the morning.
     *
     * The same judgement as [select] — same bars, same mutes upstream, same dedupe —
     * with the two limits that exist for live alerts lifted: no age limit, because the
     * point is the night's news, and no quiet check, because it is being asked after.
     *
     * Each story is scored as though it had just arrived, which is how it would have been
     * judged had quiet hours not held it. Scored at its real age, the ranker's decay
     * would have sunk a 01:00 filing below every bar by 06:30, and the digest of the
     * night would always be empty.
     */
    fun digest(
        candidates: List<AlertCandidate>,
        watchlist: Map<String, WatchTier>,
        alreadyNotified: Set<String>,
        settings: AlertSettings,
        nowMillis: Long,
        weights: Map<String, Double> = emptyMap(),
        levels: Map<String, CompanyAlertLevel> = emptyMap(),
        limit: Int = DIGEST_LIMIT,
    ): List<Alert> {
        val originals = candidates.associateBy { it.id }
        return select(
            candidates = candidates.map { it.copy(publishedAt = nowMillis) },
            watchlist = watchlist,
            alreadyNotified = alreadyNotified,
            settings = settings.copy(quietFrom = settings.quietUntil, maxPerSync = limit),
            nowMillis = nowMillis,
            firstRun = false,
            weights = weights,
            levels = levels,
        ).map { alert -> alert.copy(candidate = originals.getValue(alert.candidate.id)) }
    }

    const val DIGEST_LIMIT = 8

    private fun ageMinutes(publishedAt: Long, nowMillis: Long): Long =
        maxOf(0L, (nowMillis - publishedAt) / 60_000)
}
