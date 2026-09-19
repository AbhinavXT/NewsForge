package com.abhinavxt.newsforge.data.model

import com.abhinavxt.newsforge.core.notify.WatchTier

/**
 * The watchlist split by who put each symbol there.
 *
 * [effective] is what the app acts on — filtering, ranking, how loudly to interrupt. The
 * other two exist because the UI has to be able to tell you *why* a name is set the way
 * it is. A chip showing Leveraged that you never tapped is alarming if the screen cannot
 * also say that it came from your open positions, and a chip you cannot clear because
 * something invisible is holding it reads as a bug.
 */
data class WatchlistView(
    val effective: Map<String, WatchTier> = emptyMap(),
    /** What you set by hand. The only part the tier chips write to. */
    val manual: Map<String, WatchTier> = emptyMap(),
    /** What the last desk snapshot said you were exposed to. Read-only here. */
    val desk: Map<String, WatchTier> = emptyMap(),
) {
    val symbols: Set<String> get() = effective.keys
}
