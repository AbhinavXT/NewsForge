package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.rank.MarketClock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** One remembered mark: cumulative volume for a symbol at a point in a session. */
data class VolumeSample(
    val symbol: String,
    val sessionDay: Long,
    val bucket: Int,
    val volume: Double,
)

/**
 * Today's volume against a normal session, mark by mark.
 *
 * Both series are indexed by bucket and the same length, with null where nothing is known
 * — today is null for marks the session has not reached yet, which is what lets the chart
 * stop the line where the day has actually got to instead of drawing it to zero.
 */
data class VolumeCurve(
    val today: List<Double?>,
    val typical: List<Double?>,
    val priorSessions: Int,
)

/** How much a name is trading against its own normal, at this point in the session. */
enum class VolumeLevel {
    /** Nothing worth saying. */
    ORDINARY,

    /** Noticeably busier than usual. */
    ACTIVE,

    /** Far outside its own pattern. */
    HEAVY,
}

/**
 * Whether a stock is trading unusually, measured against itself.
 *
 * A headline about a name already trading at three times its normal volume is a different
 * headline: either the story is out ahead of you, or something else is happening that the
 * story does not mention. Either is worth a glance, and neither is visible from the price.
 *
 * The comparison has to be like for like within the session. By 09:45 a stock has done
 * perhaps a fifth of a day's volume, so measuring it against a daily average says
 * "quiet" every morning and "busy" every afternoon regardless of what happened. Assuming
 * volume arrives evenly would be worse still — it does not, it is heavy at the open and
 * again at the close, and a flat model would cry wolf before ten every day.
 *
 * So the profile is learned rather than assumed: cumulative volume is remembered at
 * fifteen-minute marks, and today's figure at a mark is compared against the same mark on
 * previous sessions. That is empirical, needs no model of how a trading day is shaped, and
 * adapts to a stock whose own pattern is unusual.
 *
 * The cost is that it says nothing at first. A phone with three sessions of history cannot
 * know what normal looks like, and guessing from one prior day would make every Monday
 * after a quiet Friday look like a breakout. A desk-supplied `rel_volume` computed against
 * twenty real sessions is strictly better and takes precedence wherever it exists.
 */
object VolumeProfile {

    /** Marks through the session at which cumulative volume is remembered. */
    const val BUCKET_MINUTES: Int = 15

    /**
     * Prior sessions needed before a figure is published.
     *
     * Below this the median is a coin toss — one holiday-thin Friday in a sample of two
     * doubles every ratio on Monday.
     */
    const val MIN_PRIOR_SESSIONS: Int = 5

    const val ACTIVE_RATIO: Double = 1.8
    const val HEAVY_RATIO: Double = 3.0

    /**
     * Which mark of the session this instant belongs to, or null outside trading hours.
     *
     * Counted from the open rather than from midnight so the marks line up across days
     * regardless of what else changes.
     */
    fun bucketOf(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Int? {
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalTime()
        if (time < MarketClock.OPEN_TIME || time > MarketClock.CLOSE_TIME) return null
        val elapsed = (time.toSecondOfDay() - MarketClock.OPEN_TIME.toSecondOfDay()) / 60
        return elapsed / BUCKET_MINUTES
    }

    /** The day a sample belongs to, as a plain day number, for grouping across sessions. */
    fun sessionDay(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate().toEpochDay()

    /**
     * @param today cumulative volume so far, at this mark.
     * @param priorSessions the same mark on previous days, in any order.
     * @return the ratio, or null when there is not enough history to mean anything.
     */
    fun relative(today: Double, priorSessions: List<Double>): Double? {
        if (today <= 0.0) return null
        val usable = priorSessions.filter { it > 0.0 }
        if (usable.size < MIN_PRIOR_SESSIONS) return null
        // Median, not mean: one results day or one block deal in the sample would drag an
        // average far enough to hide a genuinely busy session behind it.
        val sorted = usable.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
        if (median <= 0.0) return null
        return today / median
    }

    fun levelOf(ratio: Double?): VolumeLevel = when {
        ratio == null -> VolumeLevel.ORDINARY
        ratio >= HEAVY_RATIO -> VolumeLevel.HEAVY
        ratio >= ACTIVE_RATIO -> VolumeLevel.ACTIVE
        else -> VolumeLevel.ORDINARY
    }

    /**
     * Marks in a full session: 09:15 to 15:30 is 375 minutes.
     *
     * Derived rather than written down, so a change to the exchange's hours moves the
     * chart with everything else.
     */
    val BUCKETS: Int =
        (MarketClock.CLOSE_TIME.toSecondOfDay() - MarketClock.OPEN_TIME.toSecondOfDay()) /
            60 / BUCKET_MINUTES

    /**
     * Today's cumulative volume against what a normal session looks like by the same mark.
     *
     * The single ratio [relative] returns says how busy a stock is; this says *when* it
     * got busy, which is a different fact and often the more useful one. Volume that
     * arrived in the first twenty minutes and then stopped is a reaction to something
     * overnight; the same total accumulated steadily through the afternoon is not, and a
     * ratio of 2.1 describes both identically.
     */
    fun curve(
        samples: List<VolumeSample>,
        todayDay: Long,
    ): VolumeCurve? {
        if (samples.isEmpty()) return null
        val today = arrayOfNulls<Double>(BUCKETS)
        // Prior sessions collected per mark, so each point is a median of the same moment
        // in the day rather than of the day as a whole.
        val prior = Array(BUCKETS) { ArrayList<Double>() }

        for (sample in samples) {
            val bucket = sample.bucket
            if (bucket !in 0 until BUCKETS) continue
            if (sample.sessionDay == todayDay) {
                today[bucket] = sample.volume
            } else if (sample.volume > 0.0) {
                prior[bucket] += sample.volume
            }
        }

        val sessions = samples.asSequence()
            .filter { it.sessionDay != todayDay }
            .map { it.sessionDay }
            .distinct()
            .count()
        if (sessions < MIN_PRIOR_SESSIONS) return null

        val typical = prior.map { values ->
            if (values.size < MIN_PRIOR_SESSIONS) null else median(values)
        }
        if (typical.all { it == null }) return null
        return VolumeCurve(today = today.toList(), typical = typical, priorSessions = sessions)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** "3.2x" — fixed to one decimal so a column of them lines up under the mono face. */
    fun label(ratio: Double): String = String.format(java.util.Locale.US, "%.1fx vol", ratio)
}
