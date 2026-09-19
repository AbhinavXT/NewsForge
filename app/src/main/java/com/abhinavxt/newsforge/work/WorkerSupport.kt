package com.abhinavxt.newsforge.work

import android.util.Log
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.notify.DeskAlerts
import com.abhinavxt.newsforge.core.notify.NotificationPolicy
import com.abhinavxt.newsforge.di.AppContainer
import kotlinx.coroutines.CancellationException

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
 * Polls the desk bridge and applies whatever exposure snapshot came back.
 *
 * Shared by both workers because both need it before they alert, and because the ordering
 * is the part that is easy to get wrong: tiers decide who gets interrupted, so a snapshot
 * applied after a refresh judges new headlines against stale positions.
 *
 * Two separate [tolerate] calls, not one. A bridge that is down should not stop a
 * snapshot already in hand from being applied, and a failure to write the watchlist
 * should not read as a network problem in the log.
 */
internal suspend fun syncDesk(container: AppContainer) {
    val result = tolerate(TAG_DESK, "desk bridge sync") {
        container.deskRepository.sync()
    } ?: return

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
        container.repository.pricedSymbols()
    } ?: return
    tolerate(TAG_QUOTES, "exchange quotes") { container.quoteRepository.refresh(wanted) }
}

private const val TAG_QUOTES = "QuoteSync"
