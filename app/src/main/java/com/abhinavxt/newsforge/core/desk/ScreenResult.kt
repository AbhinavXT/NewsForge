package com.abhinavxt.newsforge.core.desk

import java.util.Locale

/**
 * A named list of symbols the desk's screener picked out.
 *
 * The other half of a bridge that already carries exposure. Positions say what you are
 * holding; a screen says what your own analysis surfaced this morning and has no opinion
 * about yet — the twelve names that passed a momentum filter, or the eight showing a
 * breakdown. Those are precisely the names whose news you want in front of you, and until
 * now the only way to act on a screen was to remember it and type tickers by hand.
 *
 * Deliberately not the watchlist. A screen is transient and regenerated whole each run; a
 * watchlist entry is a decision that outlives it. Merging the two would mean either
 * today's screen quietly unfollowing yesterday's names, or a watchlist that accretes every
 * name that has ever passed a filter.
 *
 * ## Wire format
 *
 * ```json
 * {"nf":1,"kind":"screen","ts":1789600000,
 *  "s":{"Momentum":"BEL,HCLTECH,SBIN","Breakdown":"TATASTEEL"}}
 * ```
 *
 * Keyed by name, symbols comma-separated — the same shape the quote batch and the position
 * weights use, so it decodes through the flattener with nothing new. A screen present with
 * an empty list is a run that matched nothing and clears the previous one; a screen simply
 * absent is left alone, because one message need not know about every screen there is.
 */
data class ScreenResult(
    val name: String,
    val symbols: List<String>,
    val takenAtMillis: Long?,
) {
    val isEmpty: Boolean get() = symbols.isEmpty()
}

object ScreenResults {

    const val KIND: String = "screen"

    private const val PREFIX = "s."

    fun fromFlat(record: Map<String, String?>): List<ScreenResult> {
        val version = record[DeskPayloads.VERSION_KEY]?.trim()?.toIntOrNull() ?: return emptyList()
        if (version != DeskPayloads.SUPPORTED_VERSION) return emptyList()
        if (record["kind"]?.trim()?.lowercase(Locale.US) != KIND) return emptyList()

        val takenAt = record["ts"]?.trim()?.toLongOrNull()?.times(1000)
        val out = ArrayList<ScreenResult>()
        for ((key, value) in record) {
            if (!key.startsWith(PREFIX)) continue
            // Names keep the sender's casing: they are labels on a chip, not identifiers
            // to match on, and "Momentum" reads better than "MOMENTUM".
            val name = key.removePrefix(PREFIX).trim()
            if (name.isEmpty()) continue
            out += ScreenResult(
                name = name,
                symbols = value.orEmpty()
                    .split(',')
                    .map { it.trim().uppercase(Locale.US) }
                    .filter { it.isNotEmpty() }
                    .distinct(),
                takenAtMillis = takenAt,
            )
        }
        return out.sortedBy { it.name.lowercase(Locale.US) }
    }
}
