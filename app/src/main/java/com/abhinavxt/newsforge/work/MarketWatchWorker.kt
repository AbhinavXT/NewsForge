package com.abhinavxt.newsforge.work

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
 * 15:45 rather than lingering — a watch that outlives the session is just battery drain.
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

        return try {
            setForeground(foregroundInfo("Watching the market"))
            runSession(container)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "market watch ended early", e)
            Result.success()
        } finally {
            // Always queue the next day, even after a failure: a watch that silently stops
            // forever is worse than one that has a bad afternoon.
            reschedule(context)
        }
    }

    private suspend fun runSession(container: com.abhinavxt.newsforge.di.AppContainer) {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (!MarketSchedule.isSessionActive(now)) return

            val report = runCatching { container.repository.refresh() }.getOrNull()
            runCatching { container.deskRepository.sync() }

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

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
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

        /** Longest the worker will wait in-process rather than handing back to scheduling. */
        private const val MAX_PREROLL_MS = 20L * 60 * 1000

        fun reschedule(context: Context) {
            val delay = MarketSchedule.millisUntilNextSession(System.currentTimeMillis())
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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
