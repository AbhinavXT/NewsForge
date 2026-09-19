package com.abhinavxt.newsforge.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Re-emits the clock on a fixed cadence, starting immediately.
 *
 * Several reads in this app are bounded by "now" — the feed's retention window, the
 * price-sample window, how long a desk quote still counts as live — and a Room flow only
 * re-emits when its table changes. Without something to drive time forward those bounds
 * are frozen at whatever the clock read when the flow was built, which is fine for a
 * screen opened and closed in a minute and wrong for the case this app is actually used
 * in: left running through a session, or open overnight. The symptoms are quiet ones. A
 * retention window that stopped sliding at 22:00, a price that stopped being live at
 * lunch and is still drawn as current at three.
 *
 * Cold on purpose, so it starts and stops with its collector and costs nothing while
 * nobody is subscribed. The emitted value is the reading to bound against, so callers
 * never have to call the clock a second time and get a different answer.
 */
internal fun ticks(periodMillis: Long, clock: () -> Long): Flow<Long> = flow {
    while (true) {
        emit(clock())
        delay(periodMillis)
    }
}

/**
 * How often a retention-bounded query is rebuilt.
 *
 * Coarse deliberately. The windows this bounds are days wide, so half an hour of slack
 * costs nothing, and each tick also re-ranks with a fresh clock — which is work, and
 * which reorders the list. A sync already does both every few minutes during the session,
 * so this only ever matters on a screen nobody is refreshing.
 */
internal const val WINDOW_TICK_MS = 30L * 60 * 1000

/**
 * How often a freshness check is re-run.
 *
 * Much tighter than [WINDOW_TICK_MS], because the thing being decided is whether a quote
 * has aged past the point of being worth showing — a question with a twenty-minute answer,
 * which half-hour granularity would be unable to express.
 */
internal const val FRESHNESS_TICK_MS = 60L * 1000
