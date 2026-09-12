package com.abhinavxt.newsforge.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abhinavxt.newsforge.MainActivity
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.notify.Alert
import com.abhinavxt.newsforge.core.notify.AlertLevel
import com.abhinavxt.newsforge.core.notify.EventAlert

/**
 * Posts alerts.
 *
 * Two channels rather than one, because Android lets a user silence a channel
 * individually — someone who mutes market-wide noise should not thereby mute stories
 * about their own holdings.
 */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HIGH,
                "Watchlist and regulatory",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Companies you follow, and regulator action"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NORMAL,
                "Market-wide",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Large deals, orders and policy across the market"
            }
        )
    }

    fun post(alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${alerts.size} alerts")
            return
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)

        for (alert in alerts) {
            val article = alert.candidate
            val channel = when (alert.level) {
                AlertLevel.HIGH -> CHANNEL_HIGH
                AlertLevel.NORMAL -> CHANNEL_NORMAL
            }
            val subtitle = buildString {
                // Exposure leads: "why am I being told this" is the first question a
                // notification has to answer.
                alert.tier?.let { append(it.label).append(" · ") }
                append(article.category.label)
                if (article.symbols.isNotEmpty()) {
                    append(" · ")
                    append(article.symbols.take(3).joinToString(" "))
                }
                append(" · ")
                append(article.sourceName)
            }

            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(article.title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(article.title))
                .setContentIntent(openIntent(article.link, article.clusterId, article.id))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                // Grouped so a burst collapses into one entry rather than filling the
                // shade — during results season a batch of four is normal.
                .setGroup(GROUP_KEY)
                .build()

            try {
                // The id is derived from the cluster so a repeat for the same story would
                // replace rather than stack, even if the dedupe table were somehow missed.
                manager.notify(article.clusterId.hashCode(), notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification refused", e)
                return
            }
        }
    }

    /**
     * Routes through [MainActivity] rather than firing ACTION_VIEW directly.
     *
     * Two reasons: the app opens the link in Custom Tabs, matching what tapping the same
     * story in the feed does, and it can mark the story read — a story you read from a
     * notification should not still be bold in the list afterwards.
     */
    /**
     * Posts reminders for scheduled events.
     *
     * Separate from [post] because these are not stories: there is no link to open and no
     * cluster to mark read. Tapping one opens the company's timeline, which is where the
     * date, the exposure and the coverage all sit together.
     */
    fun postEvents(alerts: List<EventAlert>) {
        if (alerts.isEmpty()) return
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${alerts.size} reminders")
            return
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)

        for (alert in alerts) {
            val channel = when (alert.level) {
                AlertLevel.HIGH -> CHANNEL_HIGH
                AlertLevel.NORMAL -> CHANNEL_NORMAL
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(alert.headline())
                .setContentText("${alert.tier.label} · ${alert.event.title}")
                .setContentIntent(symbolIntent(alert.event.symbol, alert.key))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setGroup(GROUP_KEY)
                .build()
            try {
                manager.notify(alert.key.hashCode(), notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Reminder refused", e)
                return
            }
        }
    }

    private fun symbolIntent(symbol: String, requestKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SYMBOL, symbol)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openIntent(url: String, clusterId: String, requestKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra(MainActivity.EXTRA_STORY_LINK, url)
            putExtra(MainActivity.EXTRA_STORY_CLUSTER, clusterId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * On API 33+ posting requires a runtime grant; below that it is implicit. Either way
     * the user can disable the channel, which [NotificationManagerCompat] also reports.
     */
    fun canPost(): Boolean {
        val granted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        private const val TAG = "Notifier"
        const val CHANNEL_HIGH = "alerts_high"
        const val CHANNEL_NORMAL = "alerts_normal"
        private const val GROUP_KEY = "newsforge_alerts"
    }
}
