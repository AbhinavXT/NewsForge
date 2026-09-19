package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.desk.DeskMessage

/**
 * Which messages from the desk are worth interrupting for.
 *
 * TickerForge has been sending strategy signals into a log that only shows them if you
 * open the Desk tab, which is the one place you are not looking when the signal matters.
 * Meanwhile ntfy already carries a priority on every message and the app already parses
 * it — the sender has been saying "this one is urgent" all along and nothing listened.
 *
 * Priority is the whole rule, and deliberately so. The desk knows which of its own
 * messages are important and this app does not; reading the body to guess would be
 * inventing a second opinion on a question already answered upstream. Anything the desk
 * sends at ntfy's default lands in the log quietly, exactly as it does now.
 */
object DeskAlerts {

    /**
     * How stale a message may be and still be announced.
     *
     * A phone that has been off since lunch should not deliver four hours of signals at
     * once when it wakes. They are in the log, where something that old belongs.
     */
    const val MAX_AGE_MS: Long = 30L * 60 * 1000

    /** Ceiling per sync, for the same reason the news alerts have one. */
    const val MAX_PER_SYNC: Int = 3

    /**
     * @param alreadyNotified message ids announced before. The poll deliberately overlaps
     *   its watermark, so the same message is seen more than once and must not be
     *   announced more than once.
     */
    fun select(
        messages: List<DeskMessage>,
        alreadyNotified: Set<String>,
        nowMillis: Long,
        quiet: Boolean,
        enabled: Boolean,
    ): List<DeskMessage> {
        if (!enabled || quiet) return emptyList()
        return messages
            .asSequence()
            .filter { it.isHighPriority }
            // A quote batch arriving at high priority is a misconfigured sender, not a
            // signal. Announcing prices would train the reader to dismiss the channel,
            // which costs the actual signals their only advantage.
            .filter { it.payloads.isEmpty() }
            // And never the app's own data. `payloads` is empty for a candle batch — the
            // quote decoder correctly refuses it — so without this the only thing keeping
            // five kilobytes of digits off the lock screen is the sender having chosen a
            // low priority.
            .filterNot { it.machine }
            .filter { it.id !in alreadyNotified }
            .filter { nowMillis - it.receivedAtMillis <= MAX_AGE_MS }
            .sortedByDescending { it.receivedAtMillis }
            .take(MAX_PER_SYNC)
            .toList()
    }

    /** Keyed apart from cluster ids, which share the notified table. */
    fun notifiedKey(message: DeskMessage): String = "desk:${message.id}"
}
