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
import com.abhinavxt.newsforge.core.desk.DeskMessage
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
                CHANNEL_DESK,
                "Desk signals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Urgent messages pushed from TickerForge"
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

    /**
     * @return the alerts that actually reached the shade.
     *
     * Reported rather than assumed, because the caller marks what it posts as notified
     * and must not mark what it did not. A refusal partway through the loop used to end
     * the method while the caller went on to record every alert in the batch — so the
     * stories after the failing one were suppressed permanently, having never been shown
     * once.
     */
    fun post(alerts: List<Alert>): List<Alert> {
        if (alerts.isEmpty()) return emptyList()
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${alerts.size} alerts")
            return emptyList()
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)
        val posted = ArrayList<Alert>(alerts.size)

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
                posted += alert
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification refused", e)
                return posted
            }
        }
        return posted
    }

    /**
     * Posts reminders for scheduled events.
     *
     * Separate from [post] because these are not stories: there is no link to open and no
     * cluster to mark read. Tapping one opens the company's timeline, which is where the
     * date, the exposure and the coverage all sit together.
     */
    fun postEvents(alerts: List<EventAlert>): List<EventAlert> {
        if (alerts.isEmpty()) return emptyList()
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${alerts.size} reminders")
            return emptyList()
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)
        val posted = ArrayList<EventAlert>(alerts.size)

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
                posted += alert
            } catch (e: SecurityException) {
                Log.w(TAG, "Reminder refused", e)
                return posted
            }
        }
        return posted
    }

    /**
     * Announces a signal the desk marked urgent.
     *
     * Its own channel, so it can be silenced without silencing the news and vice versa.
     * They are different kinds of interruption from different senders, and a reader who
     * wants trade signals at high volume may well not want every regulatory headline.
     */
    fun postDesk(messages: List<DeskMessage>): List<DeskMessage> {
        if (messages.isEmpty()) return emptyList()
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${messages.size} signals")
            return emptyList()
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)
        val posted = ArrayList<DeskMessage>(messages.size)

        for (message in messages) {
            val notification = NotificationCompat.Builder(context, CHANNEL_DESK)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(message.title?.takeIf { it.isNotBlank() } ?: "Desk")
                .setContentText(message.summary)
                // The body is often several lines of a signal; collapsing it to one
                // would hide the part that decides whether to act.
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                .setContentIntent(deskIntent(message.id))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_DESK)
                .build()
            try {
                manager.notify(message.id.hashCode(), notification)
                posted += message
            } catch (e: SecurityException) {
                Log.w(TAG, "Signal refused", e)
                return posted
            }
        }
        return posted
    }

    /** Opens the desk log rather than a story: the message is not about an article. */
    private fun deskIntent(requestKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_DESK, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    /**
     * Routes through [MainActivity] rather than firing ACTION_VIEW directly.
     *
     * Two reasons: the app opens the link in Custom Tabs, matching what tapping the same
     * story in the feed does, and it can mark the story read — a story you read from a
     * notification should not still be bold in the list afterwards.
     */
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
        const val CHANNEL_DESK = "alerts_desk"
        private const val GROUP_KEY = "newsforge_alerts"
        private const val GROUP_DESK = "newsforge_desk"
    }
}
