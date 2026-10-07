package com.abhinavxt.newsforge.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abhinavxt.newsforge.MainActivity
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.notify.Alert
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.notify.AlertAction
import com.abhinavxt.newsforge.core.notify.AlertActionButton
import com.abhinavxt.newsforge.core.notify.AlertReasons
import com.abhinavxt.newsforge.core.notify.AlertDigests
import com.abhinavxt.newsforge.core.notify.AlertLevel
import com.abhinavxt.newsforge.core.notify.EventAlert
import com.abhinavxt.newsforge.core.notify.NotificationActions
import com.abhinavxt.newsforge.core.notify.ShadeAlert
import com.abhinavxt.newsforge.core.quote.PriceAlertHit
import com.abhinavxt.newsforge.core.quote.PriceAlerts
import com.abhinavxt.newsforge.core.world.KeywordAlert
import com.abhinavxt.newsforge.data.model.ArticleSummary

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
                CHANNEL_PRICE,
                "Price alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Levels you set on a company"
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
        // Its own channel, so general news can be silenced without touching anything
        // about the market, and the other way round.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WORLD,
                "World news",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Stories about what you follow, and the evening world digest"
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
        // Counted before posting: whether a story rings on itself or leaves it to the
        // summary is fixed when it is built, so the decision cannot wait for the loop.
        val batchSize = alerts.groupingBy { it.level }.eachCount()

        for (alert in alerts) {
            val article = alert.candidate
            val channel = channelFor(alert.level)
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

            // Derived from the cluster so a repeat for the same story replaces rather
            // than stacks. Computed here because the action buttons need it too: each
            // one dismisses the notification it was tapped on.
            val notificationId = article.clusterId.hashCode()

            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(article.title)
                .setContentText(subtitle)
                // Why it rang, in the header where it is read first, and in full once
                // expanded — see AlertReasons.
                .setSubText(AlertReasons.short(alert))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${article.title}\n\nWhy: ${AlertReasons.long(alert)}")
                )
                .setContentIntent(openIntent(article.link, article.clusterId, article.id))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                // Grouped per level so a burst collapses under one summary rather than
                // filling the shade — during results season a batch of four is normal.
                // Per level because a summary belongs to one channel, and the two
                // channels exist to be silenced separately.
                .setGroup(groupFor(alert.level))
                .setGroupAlertBehavior(
                    if (AlertDigests.ringsOnSummary(batchSize[alert.level] ?: 0)) {
                        NotificationCompat.GROUP_ALERT_SUMMARY
                    } else {
                        NotificationCompat.GROUP_ALERT_CHILDREN
                    }
                )
                // Read back by the summary, which only has the shade to go on.
                .addExtras(
                    Bundle().apply {
                        putStringArray(EXTRA_ALERT_SYMBOLS, article.symbols.toTypedArray())
                        putBoolean(EXTRA_ALERT_FOLLOWED, alert.tier != null)
                        putString(EXTRA_ALERT_CLUSTER, article.clusterId)
                    }
                )

            for (button in NotificationActions.forAlert(
                symbols = article.symbols,
                sourceName = article.sourceName,
                // A Watch button on a name already followed would at best be a no-op
                // wearing a label, and on a silenced holding would reset its tier.
                followed = alert.followed,
            )) {
                builder.addAction(actionButton(button, article.id, notificationId))
            }

            val notification = builder.build()

            try {
                manager.notify(notificationId, notification)
                posted += alert
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification refused", e)
                break
            }
        }

        // After the loop, so the summary counts this batch alongside whatever earlier
        // syncs left in the shade. Only for levels that gained something: a summary
        // re-rung for a group nothing was added to would be a buzz about old news.
        for (level in posted.mapTo(LinkedHashSet()) { it.level }) {
            postSummary(level, ring = AlertDigests.ringsOnSummary(batchSize[level] ?: 0))
        }
        return posted
    }

    /**
     * Brings the summaries in line with the shade after a story was dismissed in code.
     *
     * The system clears a summary when the user swipes away its last child, but not
     * reliably when the app cancels it — and an action button cancels in code. Silent: a
     * group shrinking is not news.
     *
     * @param dismissedId a story just cancelled. Excluded by hand because the cancel is
     *   queued, and the shade read straight after it can still list the story.
     */
    fun refreshSummaries(dismissedId: Int? = null) {
        for (level in AlertLevel.entries) postSummary(level, ring = false, dismissedId)
    }

    /**
     * Posts, updates or clears the summary for one level.
     *
     * Posted even over a single story. Android draws a group of one as that story, so
     * the summary costs nothing there, and having it already in place is what lets the
     * next story join a group rather than start a second loose entry.
     */
    private fun postSummary(level: AlertLevel, ring: Boolean, dismissedId: Int? = null) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val group = groupFor(level)
        val summaryId = summaryIdFor(level)
        val children = try {
            manager.activeNotifications
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not read the shade", e)
            return
        }.filter { sbn ->
            sbn.id != dismissedId &&
                sbn.notification.group == group &&
                sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }

        val digest = AlertDigests.of(
            level,
            children.map { sbn ->
                val extras = sbn.notification.extras
                ShadeAlert(
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    symbols = extras.getStringArray(EXTRA_ALERT_SYMBOLS)?.toList().orEmpty(),
                    followed = extras.getBoolean(EXTRA_ALERT_FOLLOWED, false),
                    postedAt = sbn.postTime,
                )
            },
        )
        if (digest == null) {
            manager.cancel(summaryId)
            return
        }

        val style = NotificationCompat.InboxStyle().setBigContentTitle(digest.title)
        digest.lines.forEach { style.addLine(it) }
        if (digest.more > 0) style.setSummaryText("+${digest.more} more")

        val summary = NotificationCompat.Builder(context, channelFor(level))
            .setSmallIcon(R.drawable.ic_stat_newsforge)
            .setContentTitle(digest.title)
            .setContentText(digest.lines.firstOrNull())
            .setStyle(style)
            .setContentIntent(feedIntent(group))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSilent(!ring)
            // Clearing the pile from the shade, once the summary has said what is in it.
            .addAction(
                R.drawable.ic_done_all,
                "Mark all read",
                markAllReadIntent(
                    clusterIds = children.mapNotNull {
                        it.notification.extras.getString(EXTRA_ALERT_CLUSTER)
                    },
                    notificationIds = children.map { it.id } + summaryId,
                    requestKey = "mark-all#$group",
                ),
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(summaryId, summary)
        } catch (e: SecurityException) {
            Log.w(TAG, "Summary refused", e)
        }
    }

    private fun channelFor(level: AlertLevel): String = when (level) {
        AlertLevel.HIGH -> CHANNEL_HIGH
        AlertLevel.NORMAL -> CHANNEL_NORMAL
    }

    private fun groupFor(level: AlertLevel): String = when (level) {
        AlertLevel.HIGH -> GROUP_HIGH
        AlertLevel.NORMAL -> GROUP_NORMAL
    }

    private fun summaryIdFor(level: AlertLevel): Int = "summary#${groupFor(level)}".hashCode()

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
            val notification = NotificationCompat.Builder(context, channelFor(alert.level))
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(alert.headline())
                .setContentText("${alert.tier.label} · ${alert.event.title}")
                .setContentIntent(symbolIntent(alert.event.symbol, alert.key))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                // Apart from the stories, so a reminder never inflates a summary's count
                // of things to read.
                .setGroup(GROUP_EVENTS)
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

    /**
     * Builds one action button.
     *
     * Everything but [AlertAction.CHART] goes to a receiver, because the value of these
     * buttons is acting without a cold start. Chart is the exception by definition: it
     * exists to open the app, at the research screen rather than the article, which is a
     * different destination from the one a tap on the body goes to.
     */
    private fun actionButton(
        button: AlertActionButton,
        articleId: String,
        notificationId: Int,
    ): NotificationCompat.Action {
        val icon = when (button.action) {
            AlertAction.WATCH -> R.drawable.ic_star
            AlertAction.CHART -> R.drawable.ic_chart
            AlertAction.MUTE_SOURCE -> R.drawable.ic_close
            AlertAction.SAVE -> R.drawable.ic_bookmark
            AlertAction.MARK_ALL_READ -> R.drawable.ic_done_all
            AlertAction.REARM_PRICE -> R.drawable.ic_refresh
        }
        val intent = if (button.action == AlertAction.CHART) {
            symbolIntent(button.symbol.orEmpty(), requestTag(articleId, button))
        } else {
            broadcast(button, articleId, notificationId)
        }
        return NotificationCompat.Action.Builder(icon, button.label, intent).build()
    }

    private fun broadcast(
        button: AlertActionButton,
        articleId: String,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.intentAction(button.action)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_ARTICLE_ID, articleId)
            button.symbol?.let { putExtra(NotificationActionReceiver.EXTRA_SYMBOL, it) }
            button.source?.let { putExtra(NotificationActionReceiver.EXTRA_SOURCE, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            requestKey(articleId, button),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Unique per story *and* per action.
     *
     * PendingIntent equality ignores extras, so two buttons sharing a request code and
     * differing only in what they carry are the same pending intent to the system —
     * which means the second one silently reuses the first one's payload, and Mute
     * quietly becomes Watch.
     */
    private fun requestTag(articleId: String, button: AlertActionButton): String =
        articleId + "#" + button.action.name

    private fun requestKey(articleId: String, button: AlertActionButton): Int =
        requestTag(articleId, button).hashCode()

    /**
     * Announces price alerts that have just fired.
     *
     * Their own channel: a level you set yourself is a different kind of interruption from
     * a story, and someone who silences the news should not thereby silence their stops.
     */
    fun postPriceAlerts(hits: List<PriceAlertHit>): List<PriceAlertHit> {
        if (hits.isEmpty()) return emptyList()
        if (!canPost()) {
            Log.i(TAG, "Notification permission not granted; dropping ${hits.size} price alerts")
            return emptyList()
        }
        ensureChannels()
        val manager = NotificationManagerCompat.from(context)
        val posted = ArrayList<PriceAlertHit>(hits.size)
        for (hit in hits) {
            val id = "price#${hit.rule.id}".hashCode()
            val notification = NotificationCompat.Builder(context, CHANNEL_PRICE)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(PriceAlerts.headline(hit))
                .setContentText(PriceAlerts.detail(hit))
                .setStyle(NotificationCompat.BigTextStyle().bigText(PriceAlerts.detail(hit)))
                .setContentIntent(symbolIntent(hit.rule.symbol, "price#${hit.rule.id}"))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setGroup(GROUP_PRICE)
                .addAction(
                    R.drawable.ic_refresh,
                    "Re-arm",
                    PendingIntent.getBroadcast(
                        context,
                        "rearm#${hit.rule.id}".hashCode(),
                        Intent(context, NotificationActionReceiver::class.java).apply {
                            action = NotificationActionReceiver.intentAction(AlertAction.REARM_PRICE)
                            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
                            putExtra(NotificationActionReceiver.EXTRA_PRICE_ALERT_ID, hit.rule.id)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            try {
                manager.notify(id, notification)
                posted += hit
            } catch (e: SecurityException) {
                Log.w(TAG, "Price alert refused", e)
                return posted
            }
        }
        return posted
    }

    /**
     * The night's held-back stories, as one notification.
     *
     * Silent unless something on it is about a followed company: a summary of the night
     * is read with coffee, not rung for, but a holding in it is still worth the sound it
     * would have made at the time.
     *
     * @return whether it reached the shade.
     */
    fun postDigest(alerts: List<Alert>): Boolean {
        if (alerts.isEmpty() || !canPost()) return false
        ensureChannels()
        val followed = alerts.any { it.tier != null }
        val title = "While you were away: ${alerts.size} " +
            if (alerts.size == 1) "story" else "stories"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        for (alert in alerts.take(AlertDigests.MAX_LINES)) {
            val symbols = alert.candidate.symbols.take(2).joinToString(" ")
            style.addLine(if (symbols.isEmpty()) alert.candidate.title else "$symbols · ${alert.candidate.title}")
        }
        if (alerts.size > AlertDigests.MAX_LINES) {
            style.setSummaryText("+${alerts.size - AlertDigests.MAX_LINES} more")
        }
        val id = DIGEST_ID
        val notification = NotificationCompat.Builder(context, if (followed) CHANNEL_HIGH else CHANNEL_NORMAL)
            .setSmallIcon(R.drawable.ic_stat_newsforge)
            .setContentTitle(title)
            .setContentText(alerts.first().candidate.title)
            .setStyle(style)
            .setContentIntent(feedIntent("digest"))
            .setAutoCancel(true)
            .setSilent(!followed)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .addAction(
                R.drawable.ic_done_all,
                "Mark all read",
                markAllReadIntent(
                    clusterIds = alerts.map { it.candidate.clusterId },
                    notificationIds = listOf(id),
                    requestKey = "mark-all#digest",
                ),
            )
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Digest refused", e)
            false
        }
    }

    /**
     * One notification per followed-keyword story, opening it in the reader.
     *
     * @return the alerts that reached the shade, for the caller to mark as notified.
     */
    fun postWorldKeywords(alerts: List<KeywordAlert>): List<KeywordAlert> {
        if (alerts.isEmpty() || !canPost()) return emptyList()
        ensureChannels()
        val posted = ArrayList<KeywordAlert>(alerts.size)
        for (alert in alerts) {
            val story = alert.candidate
            val id = "world#${story.clusterId}".hashCode()
            val notification = NotificationCompat.Builder(context, CHANNEL_WORLD)
                .setSmallIcon(R.drawable.ic_stat_newsforge)
                .setContentTitle(story.title)
                .setContentText("${alert.keyword} · ${story.sourceName}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(story.title))
                .setSubText("Following")
                .setWhen(story.publishedAt)
                .setShowWhen(true)
                .setContentIntent(openIntent(story.link, story.clusterId, "world#${story.id}"))
                .setAutoCancel(true)
                .setGroup(GROUP_WORLD)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
                posted += alert
            } catch (e: SecurityException) {
                Log.w(TAG, "World alert refused", e)
                break
            }
        }
        return posted
    }

    /**
     * The evening world digest: the day's top stories in one quiet notification.
     *
     * Silent, because it arrives on a schedule the reader chose rather than because
     * something happened, and opens the World tab rather than one story.
     *
     * @return whether it reached the shade.
     */
    fun postWorldDigest(stories: List<ArticleSummary>): Boolean {
        if (stories.isEmpty() || !canPost()) return false
        ensureChannels()
        val title = "Today in the world"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        for (story in stories) style.addLine("${story.category.label} · ${story.title}")
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_WORLD, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_WORLD)
            .setSmallIcon(R.drawable.ic_stat_newsforge)
            .setContentTitle(title)
            .setContentText(stories.first().title)
            .setStyle(style)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    "world-digest".hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .addAction(
                R.drawable.ic_done_all,
                "Mark all read",
                markAllReadIntent(
                    clusterIds = stories.map { it.clusterId },
                    notificationIds = listOf(WORLD_DIGEST_ID),
                    requestKey = "mark-all#world-digest",
                ),
            )
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(WORLD_DIGEST_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "World digest refused", e)
            false
        }
    }

    /** Marks a group's stories read and clears it, without opening the app. */
    private fun markAllReadIntent(
        clusterIds: List<String>,
        notificationIds: List<Int>,
        requestKey: String,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.intentAction(AlertAction.MARK_ALL_READ)
            putExtra(NotificationActionReceiver.EXTRA_CLUSTER_IDS, clusterIds.toTypedArray())
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_IDS, notificationIds.toIntArray())
        }
        return PendingIntent.getBroadcast(
            context,
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Opens the app at the feed, for a summary that is about several stories at once. */
    private fun feedIntent(requestKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        const val CHANNEL_PRICE = "alerts_price"
        private const val DIGEST_ID = 4301
        private const val WORLD_DIGEST_ID = 4302
        const val CHANNEL_WORLD = "world_news"
        private const val GROUP_WORLD = "newsforge_world"
        private const val GROUP_PRICE = "newsforge_price"
        private const val EXTRA_ALERT_CLUSTER = "newsforge.alert.cluster"
        private const val GROUP_HIGH = "newsforge_alerts_high"
        private const val GROUP_NORMAL = "newsforge_alerts_normal"
        private const val GROUP_EVENTS = "newsforge_events"
        private const val EXTRA_ALERT_SYMBOLS = "newsforge.alert.symbols"
        private const val EXTRA_ALERT_FOLLOWED = "newsforge.alert.followed"
        private const val GROUP_DESK = "newsforge_desk"
    }
}
