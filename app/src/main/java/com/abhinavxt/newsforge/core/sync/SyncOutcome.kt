package com.abhinavxt.newsforge.core.sync

/** What a worker tells WorkManager when its run is over. */
enum class SyncOutcome {
    /** Done, or done enough. Periodic work stays on its schedule. */
    SUCCESS,

    /** Try again sooner than the next period, with backoff. */
    RETRY,

    /** Give up permanently. Only ever correct for one-off work. */
    FAILURE,
}

/**
 * Decides what a finished sync reports, and the whole point is what it never reports.
 *
 * For periodic work there is no permanent failure to report: `Result.failure()` ends
 * this run and the request goes back to waiting for its next period, exactly as success
 * would. So the periodic worker reports success once it stops retrying — same scheduling,
 * without a run history that reads as broken. A one-off has no next period, so failure
 * is the honest report there.
 *
 * Retry is bounded for the same reason it is used at all. On periodic work a retry does
 * not run alongside the schedule, it replaces the next occurrence with a backed-off one,
 * and the backoff is exponential to a five-hour ceiling. Two or three quick retries are
 * worth having for a blip; beyond that the ordinary fifteen-minute period is sooner than
 * the backoff would be, so waiting for it is both simpler and faster.
 */
object SyncOutcomes {

    /** Attempts before a periodic run stops retrying and waits for its next turn. */
    const val MAX_ATTEMPTS = 3

    /**
     * @param periodic true for the scheduled worker, false for a one-off.
     * @param attempt `runAttemptCount`, zero on the first try of a run.
     * @param recoverable whether trying again could plausibly help — a network that was
     *   down, rather than a bug that will throw again identically.
     */
    fun decide(periodic: Boolean, attempt: Int, recoverable: Boolean): SyncOutcome = when {
        recoverable && attempt < MAX_ATTEMPTS - 1 -> SyncOutcome.RETRY
        // A one-off has no next turn, so failure is the honest report; the periodic
        // worker has one in fifteen minutes either way.
        periodic -> SyncOutcome.SUCCESS
        else -> SyncOutcome.FAILURE
    }
}
