package com.abhinavxt.newsforge.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOutcomeTest {

    private fun periodic(attempt: Int, recoverable: Boolean = true) =
        SyncOutcomes.decide(periodic = true, attempt = attempt, recoverable = recoverable)

    private fun oneOff(attempt: Int, recoverable: Boolean = true) =
        SyncOutcomes.decide(periodic = false, attempt = attempt, recoverable = recoverable)

    @Test
    fun thePeriodicWorkerNeverReportsAPermanentFailure() {
        // The bug this exists for. Failure is terminal for periodic work: WorkManager
        // stops scheduling it, and nothing re-enqueues it until the app is opened — so
        // the app quietly becomes one that only notifies while you are looking at it.
        for (attempt in 0..50) {
            for (recoverable in listOf(true, false)) {
                val outcome = periodic(attempt, recoverable)
                assertEquals(
                    "attempt $attempt, recoverable=$recoverable",
                    false,
                    outcome == SyncOutcome.FAILURE,
                )
            }
        }
    }

    @Test
    fun aBlipIsRetriedQuickly() {
        assertEquals(SyncOutcome.RETRY, periodic(attempt = 0))
        assertEquals(SyncOutcome.RETRY, periodic(attempt = 1))
    }

    @Test
    fun thenItWaitsForItsNextTurnRatherThanBackingOffForHours() {
        // A retry on periodic work replaces the next occurrence with a backed-off one,
        // and the backoff climbs to a five-hour ceiling. The ordinary fifteen-minute
        // period is sooner than that, so there is nothing to gain by continuing.
        assertEquals(SyncOutcome.SUCCESS, periodic(attempt = 2))
        assertEquals(SyncOutcome.SUCCESS, periodic(attempt = 9))
    }

    @Test
    fun anUnrecoverableRunDoesNotRetryAtAll() {
        assertEquals(SyncOutcome.SUCCESS, periodic(attempt = 0, recoverable = false))
        assertEquals(SyncOutcome.FAILURE, oneOff(attempt = 0, recoverable = false))
    }

    @Test
    fun aOneOffStillFailsWhenItIsOutOfAttempts() {
        // It has no next turn, so failure is the honest report — and pull-to-refresh
        // showing an error is the point.
        assertEquals(SyncOutcome.RETRY, oneOff(attempt = 0))
        assertEquals(SyncOutcome.RETRY, oneOff(attempt = 1))
        assertEquals(SyncOutcome.FAILURE, oneOff(attempt = 2))
    }
}
