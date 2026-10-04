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
import androidx.work.workDataOf
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.newsforge.NewsForgeApp
import com.abhinavxt.newsforge.core.sync.SyncOutcome
import com.abhinavxt.newsforge.core.sync.SyncOutcomes
import com.abhinavxt.newsforge.core.mute.MuteRules
import com.abhinavxt.newsforge.core.notify.EventAlertPolicy
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

    /**
     * True for the run WorkManager repeats, false for a one-off.
     *
     * Carried in the input data rather than inferred from tags, because the two runs use
     * the same worker class and the difference decides whether this worker is allowed to
     * report a permanent failure. Getting it from something incidental would be a subtle
     * way to end up back where this started.
     */
    private val periodic: Boolean get() = inputData.getBoolean(SyncScheduler.KEY_PERIODIC, false)

    /**
     * Translates a bad run into something WorkManager can act on.
     *
     * See [SyncOutcomes] for why the periodic worker must never report failure.
     */
    private fun outcome(recoverable: Boolean): Result =
        when (SyncOutcomes.decide(periodic, runAttemptCount, recoverable)) {
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.FAILURE -> Result.failure()
        }

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
            // After quotes, so the sweep sees the prices this sync just sampled; before
            // alerts, only because it is cheap and ordering it last would change nothing.
            // Tolerated like the others: a model that fails to learn this cycle is a
            // model that learns next cycle, not a failed sync.
            tolerate(TAG, "importance") { container.importanceRepository.train() }
            // Tolerated: a notification that fails to post is not a failed sync, and
            // retrying the whole refresh would not help it.
            tolerate(TAG, "story alerts") { postStoryAlerts(container) }
            postEventReminders(container)
            // Last: copies of saved stories for offline reading are the least urgent
            // thing a sync does, and each is a page download.
            tolerate(TAG, "offline copies") { container.savedArticles.backfill() }
            tolerate(TAG, "reading positions") { container.readerRepository.prune() }
            Log.i(
                TAG,
                "sync: ${report.newArticles} new, ${report.failedFeeds.size} feeds failed, " +
                    "${report.prunedArticles} pruned",
            )
            // Every feed failing almost always means no usable network rather than dead
            // feeds, and that is worth a backed-off retry. A few failures are not.
            if (report.allFailed) outcome(recoverable = true) else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            outcome(recoverable = true)
        }
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
            levels = repository.alertLevelsNow(),
        )
        if (alerts.isEmpty()) return

        val posted = container.notifier.postEvents(alerts)
        if (posted.isEmpty()) return
        repository.markNotified(posted.map { it.key })
    }

    companion object {
        private const val TAG = "SyncWorker"

    }
}

object SyncScheduler {

    private const val PERIODIC_NAME = "newsforge-periodic-sync"

    /** Marks the repeating run, so it knows not to report a permanent failure. */
    const val KEY_PERIODIC = "periodic"
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
            .setInputData(workDataOf(KEY_PERIODIC to true))
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
