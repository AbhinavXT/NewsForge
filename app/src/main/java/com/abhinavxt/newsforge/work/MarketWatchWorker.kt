package com.abhinavxt.newsforge.work

import com.abhinavxt.newsforge.core.model.Desk

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.newsforge.NewsForgeApp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.rank.MarketSchedule
import com.abhinavxt.newsforge.core.rank.PollingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit

/**
 * Keeps the app polling through the trading session.
 *
 * Background sync is pinned to WorkManager's fifteen-minute floor, which is fine at 22:00
 * and useless at 09:20. This is the only way to poll faster than that without the app
 * being open: a long-running worker promoted to the foreground, which Android allows from
 * WorkManager and does not allow from an ordinary background service start.
 *
 * It costs a permanent notification while it runs, so it is opt-in, and it stops itself at
 * the last segment rather than lingering — a watch that outlives the session is just
 * battery drain. The day is split in two; see [MarketSchedule.SEGMENTS] for why.
 */
class MarketWatchWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Watching the market")

    override suspend fun doWork(): Result {
        val container = (applicationContext as NewsForgeApp).container
        if (!container.watchPreferences.enabled) return Result.success()

        // Scheduled ahead of the open, so the first thing it does is usually wait.
        val untilOpen = MarketSchedule.millisUntilNextSession(System.currentTimeMillis())
        if (untilOpen > 0) {
            // Long waits are left to the scheduler rather than slept through: holding a
            // foreground worker overnight to start at 09:00 would show the notification
            // all night for nothing.
            if (untilOpen > MAX_PREROLL_MS) {
                reschedule(context)
                return Result.success()
            }
            delay(untilOpen)
        }

        val result = try {
            setForeground(foregroundInfo("Watching the market"))
            // Started, so whatever blocked an earlier segment no longer applies.
            container.watchPreferences.lastRefusedAt = 0L
            runSession(container)
            Result.success()
        } catch (e: CancellationException) {
            // Not rescheduled, and not in a `finally` either. Cancellation is either the
            // user turning the watch off — where re-arming would quietly undo it — or
            // WorkManager stopping us, which re-runs the request on its own.
            throw e
        } catch (e: Exception) {
            if (isForegroundRefusal(e)) {
                // Android 12+ will not start a foreground service for an app in the
                // background unless it is exempt, and a scheduled 09:00 start usually is
                // not. Restarting in a minute would be refused identically all segment
                // long, so this segment is given up and the next one tried — by which
                // time the app may have been opened, or exempted from battery
                // optimisation.
                Log.w(TAG, "foreground start refused; skipping to the next segment", e)
                container.watchPreferences.lastRefusedAt = System.currentTimeMillis()
                if (container.watchPreferences.enabled) rescheduleAfterCurrentSegment(context)
                return Result.success()
            }
            Log.w(TAG, "market watch ended early", e)
            Result.success()
        }

        // Queue the next session even after a failure: a watch that silently stops
        // forever is worse than one that has a bad afternoon. Re-read rather than
        // reusing the check at the top, because the session is hours long and the
        // setting may have been turned off somewhere in the middle of it.
        if (container.watchPreferences.enabled) reschedule(context)
        return result
    }

    private suspend fun runSession(container: com.abhinavxt.newsforge.di.AppContainer) {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (!MarketSchedule.isSessionActive(now)) return

            // Exposure first. The tiers decide who gets alerted and how loudly, so a
            // snapshot applied after the refresh would judge this poll's headlines
            // against the previous poll's positions — wrong in exactly the window that
            // matters, the minutes after you open or close something.
            syncDesk(container)
            val report = tolerate(TAG, "refresh") {
                // Markets only. The watch ticks every minute through the session, and
                // the world feeds have their own, much slower schedule in the sync.
                container.repository.refresh(desks = setOf(Desk.MARKETS))
            }
            syncQuotes(container)
            // Every tick, not only the periodic sync's: this is the stretch of the day an
            // alert is worth most, and the watch used to store the session's news without
            // ever ringing for any of it.
            tolerate(TAG, "alerts") { postStoryAlerts(container) }

            report?.let {
                setForeground(
                    foregroundInfo(
                        if (it.newArticles > 0) {
                            "${it.newArticles} new · ${it.outcomes.size} feeds"
                        } else {
                            "Watching the market"
                        }
                    )
                )
            }

            val interval = PollingPolicy.intervalMillisAt(System.currentTimeMillis())
                ?: return
            delay(interval)
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_newsforge)
            .setContentTitle("NewsForge")
            .setContentText(text)
            // Silent and minimal: this is a status line, not an alert. Real alerts still
            // arrive on their own channels.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Unconditional: minSdk is 30, so the pre-29 branch this used to carry could
        // never be taken, and a dead branch around a service type is the kind that gets
        // trusted when the minimum later moves.
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live watch", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown while polling through the trading session" }
        )
    }

    companion object {
        private const val TAG = "MarketWatch"
        private const val CHANNEL_ID = "market_watch"
        private const val NOTIFICATION_ID = 4201
        private const val UNIQUE_NAME = "newsforge-market-watch"

        /**
         * Longest the worker will wait in-process rather than handing back to scheduling.
         *
         * Under WorkManager's ten-minute limit for work that is not yet in the
         * foreground, with margin: the wait happens before [setForeground], and a longer
         * one was stopped by the system partway through and rerun.
         */
        private const val MAX_PREROLL_MS = 5L * 60 * 1000

        /**
         * Shortest gap before the watch is allowed to start again.
         *
         * [MarketSchedule.millisUntilNextSession] returns zero while a session is already
         * running, which is the recovery path — the worker died at 11:40 and should come
         * back. Restarting with no delay makes that a spin instead: if whatever killed it
         * is going to kill it again, `setForeground` being refused being the obvious
         * candidate, it would relaunch continuously to the end of the segment.
         */
        private const val RESTART_DELAY_MS = 60L * 1000

        fun reschedule(context: Context) {
            val untilNext = MarketSchedule.millisUntilNextSession(System.currentTimeMillis())
            enqueue(context, if (untilNext <= 0L) RESTART_DELAY_MS else untilNext)
        }

        private fun enqueue(context: Context, delay: Long) {
            val request = OneTimeWorkRequestBuilder<MarketWatchWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()
            // REPLACE, not KEEP: the delay is recomputed each time, and keeping a stale
            // one would pin the watch to yesterday's start.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Skips whatever remains of the segment in progress. */
        private fun rescheduleAfterCurrentSegment(context: Context) {
            val now = System.currentTimeMillis()
            val next = MarketSchedule.nextSessionStartMillis(MarketSchedule.sessionEndMillis(now))
            enqueue(context, (next - now).coerceAtLeast(RESTART_DELAY_MS))
        }

        /**
         * True when the platform refused the foreground start, however WorkManager
         * wrapped it on the way out.
         */
        private fun isForegroundRefusal(e: Throwable): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                generateSequence(e) { it.cause }
                    .any { it is ForegroundServiceStartNotAllowedException }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
