package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.desk.BoardRow
import com.abhinavxt.newsforge.core.desk.BoardSnapshot
import com.abhinavxt.newsforge.core.rank.MarketClock

/**
 * Which order-block setups on the desk's board are worth interrupting for.
 *
 * The board ranks forty names a minute. Ringing for any of them would be a ticker tape on
 * the lock screen, so this rings only for the two flags that mean "act now, or decide not
 * to" — a liquidity trap, and a reversal building at the order block — and only on names
 * you follow. Everything else stays on the board, where you go to look.
 *
 * Once per name, flag and session day. A trap is still flagged on the next board a minute
 * later and the one after that; it happened once and is announced once.
 */
object BoardAlerts {

    /** A closing board read at 20:00 is history, not a signal. */
    const val MAX_AGE_MS: Long = 15L * 60 * 1000

    const val MAX_PER_BOARD: Int = 3

    enum class Kind { TRAP, REVERSAL }

    data class Alert(val row: BoardRow, val kind: Kind, val key: String) {
        val symbol: String get() = row.symbol

        /** "SBIN · liquidity trap ▲ PDL" — the flag, in the words the board uses. */
        val title: String
            get() = when (kind) {
                Kind.TRAP -> {
                    val trap = row.trap
                    val arrow = if (trap?.bullish == true) "▲" else "▼"
                    "${row.symbol} · liquidity trap $arrow ${trap?.label.orEmpty()}".trim()
                }
                Kind.REVERSAL -> "${row.symbol} · reversal building at the order block"
            }
    }

    /**
     * @param followed symbols on the watchlist or held, as the store keys them.
     * @param alreadyNotified keys announced before, from the shared notified table.
     */
    fun select(
        board: BoardSnapshot,
        followed: Set<String>,
        alreadyNotified: Set<String>,
        nowMillis: Long,
        quiet: Boolean,
        enabled: Boolean,
    ): List<Alert> {
        if (!enabled || quiet || followed.isEmpty()) return emptyList()
        // Only a board built during the session. A cached or closing board repeats flags
        // that were true hours ago, and announcing one now would be announcing the past.
        if (!board.live || board.stale) return emptyList()
        val taken = board.takenAtMillis ?: return emptyList()
        if (nowMillis - taken > MAX_AGE_MS) return emptyList()

        val day = java.time.Instant.ofEpochMilli(taken).atZone(MarketClock.ZONE).toLocalDate().toString()
        val out = ArrayList<Alert>()
        // Bull and bear interleaved by rank, so the strongest setup on either side goes
        // first when the ceiling cuts the list.
        for (row in (board.bull + board.bear).sortedBy { it.rank }) {
            if (row.symbol !in followed) continue
            if (row.trap != null) {
                val key = key(row, Kind.TRAP, day)
                if (key !in alreadyNotified) out += Alert(row, Kind.TRAP, key)
            }
            if (row.reversal) {
                val key = key(row, Kind.REVERSAL, day)
                if (key !in alreadyNotified) out += Alert(row, Kind.REVERSAL, key)
            }
        }
        return out.take(MAX_PER_BOARD)
    }

    /** Keyed apart from cluster ids and desk messages, which share the notified table. */
    fun key(row: BoardRow, kind: Kind, day: String): String =
        "board:$day:${row.symbol}:${kind.name}:${row.trap?.let { (if (it.bullish) "+" else "-") + it.label }.orEmpty()}"
}
