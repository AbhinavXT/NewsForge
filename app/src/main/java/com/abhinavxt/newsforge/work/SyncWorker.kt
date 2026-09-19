package com.abhinavxt.newsforge.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.newsforge.NewsForgeApp
import com.abhinavxt.newsforge.core.mute.MuteRules
import com.abhinavxt.newsforge.core.notify.EventAlertPolicy
import com.abhinavxt.newsforge.core.notify.NotificationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background feed sync.
 *
 * `CoroutineWorker` rather than `Worker` so that stopping the work actually cancels the
 * in-flight HTTP calls, which [com.abhinavxt.newsforge.data.net.FeedFetcher] is built to
 * respect.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NewsForgeApp).container
        val repository = container.repository
        return try {
            // Before the refresh, so alerts are judged against current exposure rather
            // than the last poll's. Still best-effort: a bridge failure must not fail
            // the news sync, which is what `tolerate` is for.
            syncDesk(container)
            // Before the refresh: a company listed this week should be taggable in the
            // articles this very sync is about to store, not in tomorrow's.
            tolerate(TAG, "company list") { container.instrumentRepository.refresh() }
            val report = repository.refresh()
            syncQuotes(container)
            postAlerts(container, report)
            postEventReminders(container)
            Log.i(
                TAG,
                "sync: ${report.newArticles} new, ${report.failedFeeds.size} feeds failed, " +
                    "${report.prunedArticles} pruned",
            )
            // Every feed failing almost always means no usable network rather than dead
            // feeds, and that is worth a backed-off retry. A few failures are not.
            if (report.allFailed) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /**
     * Alerts are decided here rather than in the repository.
     *
     * Notifications are a delivery concern, and a repository that posted them could not
     * be exercised without a NotificationManager. It also means a manual pull-to-refresh
     * from inside the app never buzzes: the reader is already looking at the list.
     */
    private suspend fun postAlerts(
        container: com.abhinavxt.newsforge.di.AppContainer,
        report: com.abhinavxt.newsforge.data.model.SyncReport,
    ) {
        val settings = container.alertPreferences.settings()
        if (!settings.enabled || report.candidates.isEmpty()) return

        val repository = container.repository
        // Muting is where most of its value is: a source silenced in the feed must not
        // be allowed to wake you at 09:20.
        val mutes = repository.muteRulesNow()
        val candidates = report.candidates.filterNot {
            MuteRules.isMuted(it.sourceName, it.title, it.symbols, mutes)
        }
        if (candidates.isEmpty()) return

        val alerts = NotificationPolicy.select(
            candidates = candidates,
            watchlist = repository.watchlistSymbols(),
            alreadyNotified = repository.alreadyNotified(report.startedAt - DEDUPE_WINDOW_MS),
            weights = repository.positionWeights(),
            settings = settings,
            nowMillis = report.finishedAt,
            firstRun = report.firstRun,
        )
        if (alerts.isEmpty()) return

        // Marked after posting, and only for what posted. A crash or a refusal mid-batch
        // then leaves the rest unmarked, so the next sync tries them again rather than
        // recording as delivered a story nobody was ever shown.
        val posted = container.notifier.post(alerts)
        if (posted.isEmpty()) return
        repository.markNotified(posted.map { it.candidate.clusterId })
    }

    /**
     * Warns about scheduled dates on followed symbols.
     *
     * Runs every sync, independent of whether anything was fetched: a results date one day
     * out needs telling about even on a morning when no feed returned a single new story.
     */
    private suspend fun postEventReminders(container: com.abhinavxt.newsforge.di.AppContainer) {
        val settings = container.alertPreferences.settings()
        if (!settings.enabled) return

        val repository = container.repository
        val events = repository.upcomingEvents().first()
        if (events.isEmpty()) return

        val mutedSymbols = repository.muteRulesNow()
        val alerts = EventAlertPolicy.select(
            events = events.filterNot {
                MuteRules.isMuted("", it.title, listOf(it.symbol), mutedSymbols)
            },
            tiers = repository.watchlistSymbols(),
            alreadyNotified = repository.alreadyNotified(
                System.currentTimeMillis() - EventAlertPolicy.DEDUPE_WINDOW_MS
            ),
            settings = settings,
            nowMillis = System.currentTimeMillis(),
        )
        if (alerts.isEmpty()) return

        val posted = container.notifier.postEvents(alerts)
        if (posted.isEmpty()) return
        repository.markNotified(posted.map { it.key })
    }

    companion object {
        private const val TAG = "SyncWorker"

        /** How far back to look for an existing alert on the same story. */
        private const val DEDUPE_WINDOW_MS = 48L * 60 * 60 * 1000
        private const val MAX_ATTEMPTS = 3
    }
}

object SyncScheduler {

    private const val PERIODIC_NAME = "newsforge-periodic-sync"
    const val ONE_OFF_NAME = "newsforge-manual-sync"

    /**
     * Fifteen minutes is WorkManager's floor for periodic work, so this is as tight as
     * background sync can be. Faster polling during market hours happens in the
     * foreground while the app is open, which is the only place it is actually useful.
     */
    private const val INTERVAL_MINUTES = 15L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        // KEEP, not UPDATE: replacing the request on every launch resets its period and a
        // frequently opened app would then never actually run a background sync.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Fires a sync now, for pull-to-refresh and first launch. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF_NAME,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
