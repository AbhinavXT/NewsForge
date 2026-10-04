package com.abhinavxt.newsforge.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.abhinavxt.newsforge.NewsForgeApp
import com.abhinavxt.newsforge.core.mute.MuteKind
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.notify.AlertAction
import com.abhinavxt.newsforge.core.notify.WatchTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Carries out an action tapped in the notification shade.
 *
 * A receiver rather than an activity, because the entire point of these buttons is not
 * having to open the app. Watching a name or silencing an outlet is a decision the reader
 * has already made by the time their thumb lands; making them wait for a cold start to
 * see it take effect would leave the buttons slower than doing it by hand.
 *
 * [AlertAction.CHART] is deliberately not handled here — it is the one action whose whole
 * purpose is to open the app, so it is wired as an activity intent by [Notifier].
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.let { name ->
            runCatching { AlertAction.valueOf(name.removePrefix(ACTION_PREFIX)) }.getOrNull()
        } ?: return
        val app = context.applicationContext as? NewsForgeApp ?: return

        // Dismissed first, synchronously, so the shade reacts at the speed of the tap
        // rather than at the speed of a database write.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NO_NOTIFICATION)
        if (notificationId != NO_NOTIFICATION) {
            NotificationManagerCompat.from(context).cancel(notificationId)
            // Otherwise a summary reading "4 updates" outlives the story it counted.
            app.container.notifier.refreshSummaries(dismissedId = notificationId)
        }
        // A summary's action clears its whole group.
        intent.getIntArrayExtra(EXTRA_NOTIFICATION_IDS)?.forEach {
            NotificationManagerCompat.from(context).cancel(it)
        }

        // Keeps the process alive past the return of this method. Without it the work
        // below races the system tearing the receiver down, and the loser is whichever
        // action the reader happened to tap while the app was not already running.
        val pending = goAsync()
        scope.launch {
            try {
                // Only on success, and only once the write is durable. A toast raised
                // before it would be a claim rather than a confirmation.
                perform(app, action, intent)?.let { message ->
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Action $action failed", e)
            } finally {
                // Last, not in flight with the toast: finishing releases the process,
                // and a confirmation posted after that may never be drawn.
                pending.finish()
            }
        }
    }

    /** @return what to tell the reader, or null when there is nothing to say. */
    private suspend fun perform(
        app: NewsForgeApp,
        action: AlertAction,
        intent: Intent,
    ): String? {
        val repository = app.container.repository
        return when (action) {
            AlertAction.WATCH -> {
                val symbol = intent.getStringExtra(EXTRA_SYMBOL)?.takeIf { it.isNotBlank() }
                    ?: return null
                // The default tier, not a guess at exposure. Following a name from a
                // headline says you want to hear about it, not that you own it, and
                // overstating that would make it interrupt you more loudly than asked.
                repository.setWatchTier(symbol, WatchTier.WATCHING)
                "Watching $symbol"
            }

            AlertAction.MUTE_SOURCE -> {
                val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it.isNotBlank() }
                    ?: return null
                repository.addMute(MuteRule(MuteKind.SOURCE, source))
                "Muted $source — undo it in Feeds"
            }

            AlertAction.SAVE -> {
                val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID)?.takeIf { it.isNotBlank() }
                    ?: return null
                repository.setSaved(articleId, true)
                // The copy for offline reading follows in the background sync, not here:
                // a receiver has seconds to live, and a page download can outlast them.
                "Saved"
            }

            AlertAction.MARK_ALL_READ -> {
                val clusters = intent.getStringArrayExtra(EXTRA_CLUSTER_IDS).orEmpty()
                for (clusterId in clusters) repository.markRead(clusterId)
                null
            }

            AlertAction.REARM_PRICE -> {
                val id = intent.getLongExtra(EXTRA_PRICE_ALERT_ID, -1L).takeIf { it >= 0 }
                    ?: return null
                app.container.priceAlertRepository.rearm(id)
                "Alert re-armed"
            }

            // Handled by an activity intent; reaching here means a stale pending intent
            // from an older build, and doing nothing is the right response to it.
            AlertAction.CHART -> null
        }
    }

    companion object {
        private const val TAG = "NotifyAction"

        /**
         * Namespaced so it cannot collide with a system action, and matched by prefix so
         * adding an action to the enum does not mean touching the manifest.
         */
        const val ACTION_PREFIX = "com.abhinavxt.newsforge.action."

        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * Sentinel for "no notification to dismiss".
         *
         * Not zero: the id is a hash of the cluster, and zero is a value a hash can
         * legitimately take. One story in four billion would have kept its notification
         * in the shade after being acted on, which is the sort of bug that gets
         * diagnosed as flakiness.
         */
        private const val NO_NOTIFICATION = Int.MIN_VALUE
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_ARTICLE_ID = "article_id"
        const val EXTRA_CLUSTER_IDS = "cluster_ids"
        const val EXTRA_NOTIFICATION_IDS = "notification_ids"
        const val EXTRA_PRICE_ALERT_ID = "price_alert_id"

        fun intentAction(action: AlertAction): String = ACTION_PREFIX + action.name

        /**
         * Survives the receiver instance, which lives only for [onReceive].
         *
         * A supervisor job so one failing action cannot cancel the scope and take every
         * later tap down with it.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
