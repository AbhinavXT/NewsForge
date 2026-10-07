package com.abhinavxt.newsforge.work

import com.abhinavxt.newsforge.core.notify.AlertCandidate
import com.abhinavxt.newsforge.core.world.KeywordAlerts
import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.mute.MuteRules
import com.abhinavxt.newsforge.core.notify.AlertSettings
import com.abhinavxt.newsforge.core.notify.DeskAlerts
import com.abhinavxt.newsforge.core.notify.NotificationPolicy
import com.abhinavxt.newsforge.core.quote.PriceAlerts
import com.abhinavxt.newsforge.data.DeskSyncResult
import com.abhinavxt.newsforge.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Runs a step that is allowed to fail without taking the worker down with it.
 *
 * Deliberately not `runCatching`. That catches `Throwable`, which includes
 * `CancellationException` — so wrapping a suspend call in it turns "this worker was
 * stopped" into "that step failed" and lets the caller carry on inside a scope that is
 * already dead. In a polling loop that means the loop keeps going until something else
 * happens to throw, and the failure is logged as if the network had misbehaved.
 *
 * Inline, so the lambda can suspend and so cancellation still propagates from inside it.
 *
 * @return the value, or null when the step failed.
 */
internal inline fun <T> tolerate(tag: String, what: String, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "$what failed", e)
        null
    }

/**
 * Polls the desk bridge, then asks it to have history ready for the watchlist.
 *
 * What the poll brings back is applied inside the poll itself, through [applyDeskSync],
 * so it no longer matters which caller — a worker or a screen — happens to poll first.
 */
internal suspend fun syncDesk(container: AppContainer) {
    tolerate(TAG_DESK, "desk bridge sync") { container.deskRepository.sync() } ?: return

    // Last, and only after a sync that worked. The replies land on a later poll, so this
    // is asking the desk to have the answer ready rather than waiting for one — there is
    // nothing to collect here and nothing downstream depends on it.
    tolerate(TAG_DESK, "candle warm-up") {
        container.candleRepository.warm(container.repository.watchlistSymbols().keys.toList())
    }
}

/**
 * Applies one desk poll's exposure snapshot and screens, and announces its urgent signals.
 *
 * Wired into [com.abhinavxt.newsforge.data.DeskRepository] as its sync sink, so it runs
 * after every successful poll whoever made it. The ordering still matters to the workers:
 * tiers decide who gets interrupted, and they poll the desk before refreshing news so a
 * snapshot is never applied after the headlines it should have judged.
 *
 * Three separate [tolerate] calls, not one. A failure to write the watchlist should not
 * cost the screens, nor stop an urgent signal from ringing.
 */
internal suspend fun applyDeskSync(container: AppContainer, result: DeskSyncResult) {
    result.positions?.let { snapshot ->
        tolerate(TAG_DESK, "position snapshot") { container.repository.applyPositions(snapshot) }
    }
    if (result.screens.isNotEmpty()) {
        tolerate(TAG_DESK, "screens") { container.repository.applyScreens(result.screens) }
    }
    tolerate(TAG_DESK, "desk signals") { announce(container, result.stored) }
}

/**
 * Passes on the messages the desk marked urgent.
 *
 * Deduped through the same `notified` table the news alerts use, keyed apart so the two
 * cannot collide. That matters here more than for news: the poll overlaps its watermark
 * deliberately, so a message is seen several times and only the first sighting should
 * ever ring.
 */
private suspend fun announce(container: AppContainer, stored: List<DeskMessage>) {
    if (stored.isEmpty()) return
    val settings = container.alertPreferences.settings()
    val now = System.currentTimeMillis()

    val chosen = DeskAlerts.select(
        messages = stored,
        alreadyNotified = container.repository.alreadyNotified(now - DeskAlerts.MAX_AGE_MS),
        nowMillis = now,
        // The same silence the news alerts respect. A trade signal at 03:00 is not more
        // actionable than a filing at 03:00, and waking someone for either is cost
        // without benefit.
        quiet = NotificationPolicy.isQuiet(now, settings),
        enabled = settings.enabled,
    )
    if (chosen.isEmpty()) return

    val posted = container.notifier.postDesk(chosen)
    if (posted.isEmpty()) return
    container.repository.markNotified(posted.map { DeskAlerts.notifiedKey(it) })
}

private const val TAG_DESK = "DeskSync"

/**
 * Refreshes exchange prices for the names currently in the news.
 *
 * After the news refresh rather than before it, unlike the desk sync: tiers change who
 * gets alerted and must be current first, but a price changes nothing about what is
 * stored or notified — it is read off the screen. Fetching it before the articles that
 * name the symbols would just price the previous cycle's feed.
 */
internal suspend fun syncQuotes(container: AppContainer) {
    val wanted = tolerate(TAG_QUOTES, "symbol scan") {
        // Price-alert names too, which may be in no story at all: an alert on a quiet
        // company is still an alert, and the exchange response is filtered to `wanted`.
        container.repository.pricedSymbols() +
            container.priceAlertRepository.armed().map { it.symbol }
    } ?: return
    tolerate(TAG_QUOTES, "exchange quotes") { container.quoteRepository.refresh(wanted) }
    tolerate(TAG_QUOTES, "price alerts") { checkPriceAlerts(container) }
}

/**
 * Fires price alerts against the freshest quotes on hand.
 *
 * Desk over exchange, the same precedence every screen uses, so an alert fires on the
 * price the reader would see if they opened the app. Each one is disarmed before it is
 * posted, and only posted if this caller is the one that disarmed it — the live watch
 * and the periodic sync can both be running, and one cross should be one buzz.
 */
internal suspend fun checkPriceAlerts(container: AppContainer) {
    val alerts = container.priceAlertRepository
    val rules = alerts.armed()
    if (rules.isEmpty()) return
    val quotes = container.quoteRepository.quotes().value +
        container.deskRepository.latestQuotes().first()
    val hits = PriceAlerts.check(rules, quotes, System.currentTimeMillis())
        .filter { alerts.markTriggered(it.rule.id, it.price) }
    container.notifier.postPriceAlerts(hits)
}

private const val TAG_QUOTES = "QuoteSync"

/**
 * Notifies about recent stories, whichever refresh stored them.
 *
 * Shared by the periodic sync and the live watch. Candidates come from the store rather
 * than from a refresh's report, so a story the open feed or the other worker fetched first
 * is still judged — see [com.abhinavxt.newsforge.data.NewsRepository.alertCandidates].
 *
 * Here rather than in the repository because notifications are a delivery concern: a
 * repository that posted them could not be exercised without a NotificationManager.
 */
internal suspend fun postStoryAlerts(container: AppContainer) {
    val preferences = container.alertPreferences
    val settings = preferences.settings()
    if (!settings.enabled) return

    val now = System.currentTimeMillis()
    val armedAt = preferences.armedAt
    if (armedAt == 0L) {
        // The first look only sets the line. Everything already stored predates it, and
        // a fresh install's first sync is hundreds of unread stories at once.
        preferences.armedAt = now
        return
    }
    // Checked before reading anything, and without moving the line: what lands overnight
    // is still a candidate at 06:30, within the age limit.
    if (NotificationPolicy.isQuiet(now, settings)) return

    val repository = container.repository
    val mutes = repository.muteRulesNow()
    // First, so the night's best stories arrive as one summary rather than each ringing
    // on its own in the minutes after quiet hours end.
    postQuietDigest(container, settings, now, armedAt, mutes)
    val candidates = repository.alertCandidates(
        publishedSince = now - settings.maxAgeMinutes * 60_000,
        fetchedAfter = armedAt,
    ).filterNot { MuteRules.isMuted(it.sourceName, it.title, it.symbols, mutes) }
    if (candidates.isEmpty()) return

    val alerts = NotificationPolicy.select(
        candidates = candidates,
        watchlist = repository.watchlistSymbols(),
        alreadyNotified = repository.alreadyNotified(now - STORY_DEDUPE_WINDOW_MS),
        weights = repository.positionWeights(),
        levels = repository.alertLevelsNow(),
        settings = settings,
        nowMillis = now,
        firstRun = false,
    )
    if (alerts.isEmpty()) return

    // Marked after posting, and only for what posted. A refusal mid-batch then leaves the
    // rest unmarked, so the next pass tries them again rather than recording as
    // delivered a story nobody was ever shown.
    val posted = container.notifier.post(alerts)
    if (posted.isEmpty()) return
    repository.markNotified(posted.map { it.candidate.clusterId })
}

/**
 * One notification, once per night, with what quiet hours held back.
 *
 * Quiet hours used to drop the night's alerts outright — the age limit had expired them
 * by morning — so a regulator acting at 23:00 on a holding was never mentioned at all.
 * Everything summarised is marked notified, so none of it rings again on its own.
 */
private suspend fun postQuietDigest(
    container: AppContainer,
    settings: AlertSettings,
    now: Long,
    armedAt: Long,
    mutes: List<MuteRule>,
) {
    val preferences = container.alertPreferences
    if (!preferences.digestEnabled) return
    val end = NotificationPolicy.lastQuietEnd(now, settings) ?: return
    if (preferences.lastDigestFor >= end) return
    // Recorded before posting: a digest that fails to post is not worth retrying all
    // morning, and one that posts twice is worse than one that never did.
    preferences.lastDigestFor = end

    val repository = container.repository
    val start = NotificationPolicy.quietStart(end, settings)
    val held = repository.alertCandidates(publishedSince = start, fetchedAfter = armedAt)
        .filter { it.publishedAt in start until end }
        .filterNot { MuteRules.isMuted(it.sourceName, it.title, it.symbols, mutes) }
    if (held.isEmpty()) return

    val chosen = NotificationPolicy.digest(
        candidates = held,
        watchlist = repository.watchlistSymbols(),
        alreadyNotified = repository.alreadyNotified(now - STORY_DEDUPE_WINDOW_MS),
        settings = settings,
        nowMillis = now,
        weights = repository.positionWeights(),
        levels = repository.alertLevelsNow(),
    )
    if (chosen.isEmpty()) return
    if (container.notifier.postDigest(chosen)) {
        repository.markNotified(chosen.map { it.candidate.clusterId })
    }
}

/** How far back to look for an existing alert on the same story. */
private const val STORY_DEDUPE_WINDOW_MS = 48L * 60 * 60 * 1000

/**
 * Rings for new world stories that mention a followed keyword.
 *
 * Under the same master switch and quiet hours as market alerts — one "alerts off" should
 * mean off — but otherwise its own rules, in [KeywordAlerts].
 */
internal suspend fun postWorldKeywordAlerts(
    container: AppContainer,
    arrivals: List<AlertCandidate>,
    firstSync: Boolean,
) {
    if (arrivals.isEmpty()) return
    val world = container.worldPreferences.current()
    if (!world.keywordAlerts || world.keywords.isEmpty()) return
    val settings = container.alertPreferences.settings()
    if (!settings.enabled) return
    val now = System.currentTimeMillis()
    if (NotificationPolicy.isQuiet(now, settings)) return

    val repository = container.repository
    val alerts = KeywordAlerts.select(
        arrivals = arrivals,
        keywords = world.keywords,
        alreadyNotified = repository.alreadyNotified(now - STORY_DEDUPE_WINDOW_MS),
        firstSync = firstSync,
    )
    if (alerts.isEmpty()) return
    val posted = container.notifier.postWorldKeywords(alerts)
    if (posted.isNotEmpty()) repository.markNotified(posted.map { it.candidate.clusterId })
}
