package com.abhinavxt.newsforge.core.rank

/**
 * How often to poll while the app is open and visible.
 *
 * WorkManager's floor of fifteen minutes is fine for background, and useless at 09:20.
 * Foreground polling exists to close that gap, but only while the screen is actually in
 * front of someone — a loop that keeps running in the background would spend battery and
 * mobile data to update a list nobody is looking at.
 *
 * The cadences are deliberately conservative. Roughly twenty feeds at a one-minute
 * interval is 1,200 requests an hour, and the only reason that is acceptable is that
 * almost all of them come back as a 304 with no body. If conditional requests ever stop
 * working for a feed, that feed becomes the expensive one — which is another reason feed
 * health is worth surfacing.
 */
object PollingPolicy {

    /** @return interval in millis, or null when foreground polling should not run. */
    fun intervalMillisFor(phase: MarketPhase): Long? = when (phase) {
        // Prices are moving; a headline is worth having within the minute.
        MarketPhase.OPEN -> 60_000L
        // Pre-open matters — this is when the gap list is being built — but nothing is
        // trading, so three minutes is plenty.
        MarketPhase.PRE_OPEN -> 180_000L
        // Results and filings land through the evening, at a much lower rate.
        MarketPhase.POST_CLOSE -> 300_000L
        // Nothing is happening. Background sync alone is enough.
        MarketPhase.WEEKEND -> null
    }

    fun intervalMillisAt(nowMillis: Long): Long? =
        intervalMillisFor(MarketClock.phase(nowMillis))
}
