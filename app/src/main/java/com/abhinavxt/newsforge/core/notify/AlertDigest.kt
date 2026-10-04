package com.abhinavxt.newsforge.core.notify

/** One story alert still in the shade, as the group summary sees it. */
data class ShadeAlert(
    val title: String,
    val symbols: List<String>,
    /** True when the story touched the watchlist, rather than reaching HIGH as regulatory. */
    val followed: Boolean,
    val postedAt: Long,
)

/** What the group summary says. [more] is how many stories did not fit as lines. */
data class AlertDigest(
    val title: String,
    val lines: List<String>,
    val more: Int,
)

/**
 * The summary that sits over a group of story alerts.
 *
 * Built from what is still in the shade, not from the batch just posted. Two stories at
 * 09:20 and two more at 09:35 are four things waiting to be read, and a summary that said
 * "2 updates" would be describing the sync rather than the shade.
 */
object AlertDigests {

    /** InboxStyle shows roughly this many before it starts truncating anyway. */
    const val MAX_LINES: Int = 5

    /**
     * Whether a batch should ring once on the summary instead of on each story.
     *
     * A single new story rings on itself, so its heads-up shows the headline rather than a
     * count. Two or more ring once on the summary — the point of bundling is that results
     * season costs one buzz per sync rather than four.
     */
    fun ringsOnSummary(newInBatch: Int): Boolean = newInBatch > 1

    /** @return null when there is nothing in the shade to summarise. */
    fun of(level: AlertLevel, alerts: List<ShadeAlert>): AlertDigest? {
        if (alerts.isEmpty()) return null
        val count = alerts.size
        val title = when {
            level == AlertLevel.NORMAL ->
                "$count market ${if (count == 1) "story" else "stories"}"
            alerts.all { it.followed } ->
                "$count ${if (count == 1) "update" else "updates"} on your watchlist"
            // HIGH also carries regulator action on names nobody follows, and calling
            // that a watchlist update would be a small lie in the most-read line.
            else ->
                "$count watchlist and regulatory ${if (count == 1) "alert" else "alerts"}"
        }
        val lines = alerts
            .sortedByDescending { it.postedAt }
            .take(MAX_LINES)
            .map(::line)
        return AlertDigest(title, lines, count - lines.size)
    }

    /** Symbols lead, because in a list of five headlines they are what the eye scans for. */
    internal fun line(alert: ShadeAlert): String =
        if (alert.symbols.isEmpty()) alert.title
        else alert.symbols.take(2).joinToString(" ") + " · " + alert.title
}
