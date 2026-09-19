package com.abhinavxt.newsforge.core.desk

import com.abhinavxt.newsforge.core.notify.WatchTier
import java.util.Locale

/**
 * What you are exposed to, as of a moment, pushed from the desk.
 *
 * The watchlist tiers were entered by hand, which meant they were wrong most of the time:
 * you buy something at 09:20 and the app carries on treating it as a name you are merely
 * tracking until you remember to go and say otherwise. Your positions already know this,
 * and TickerForge already holds the broker session — so the exposure can simply be sent,
 * the same way quotes already are.
 *
 * No Kite credentials on the phone. That is not squeamishness: a Kite access token is
 * good for one day and has to be re-issued through a browser login, so an app holding one
 * would be broken by tomorrow and would have shipped an API secret in an APK to get
 * there. The desk has a real session; this only needs the conclusion.
 *
 * ## Wire format
 *
 * One ntfy message, flat JSON, symbols comma-separated — no arrays, so it decodes through
 * the same flattener the quote payload uses:
 *
 * ```json
 * {"nf":1,"kind":"positions","ts":1789600000,
 *  "leveraged":"RELIANCE,INFY",
 *  "holding":"TATASTEEL,ITC",
 *  "watching":"BEL",
 *  "w":{"RELIANCE":8.4,"INFY":3.1,"TATASTEEL":0.6}}
 * ```
 *
 * `w` is optional and may cover any subset: percentages of the portfolio, keyed by symbol
 * the same way the quote batch keys its entries. Omitting it costs nothing — exposure
 * still works, it simply cannot tell a large position from a small one.
 *
 * Every tier field is optional, but a message carrying none of them is not a snapshot and
 * is ignored. A field that is present and empty is meaningful and says so: a flat book
 * has to be able to clear yesterday's positions, and treating that as a malformed message
 * would leave the app alerting at leveraged volume on something you sold.
 */
data class PositionSnapshot(
    val takenAtMillis: Long?,
    /** Symbol to exposure. Already de-duplicated to the strongest tier per symbol. */
    val tiers: Map<String, WatchTier>,
    /**
     * Symbol to share of the portfolio, as a percentage. Optional and often empty.
     *
     * A tier says what kind of exposure; this says how much, which is what separates the
     * position that can move the account from the one bought to have a reason to look.
     */
    val weights: Map<String, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = tiers.isEmpty()

    companion object {
        const val KIND: String = "positions"
    }
}

/** Decodes a [PositionSnapshot] out of the flattened body of an ntfy message. */
object PositionSnapshots {

    /** Wire key per tier. Lower case, matching the rest of the payload schema. */
    private val TIER_KEYS: List<Pair<String, WatchTier>> = listOf(
        "watching" to WatchTier.WATCHING,
        "holding" to WatchTier.HOLDING,
        "leveraged" to WatchTier.LEVERAGED,
    )

    fun fromFlat(record: Map<String, String?>): PositionSnapshot? {
        val version = record[DeskPayloads.VERSION_KEY]?.trim()?.toIntOrNull() ?: return null
        if (version != DeskPayloads.SUPPORTED_VERSION) return null
        if (record["kind"]?.trim()?.lowercase(Locale.US) != PositionSnapshot.KIND) return null
        // Absent is not the same as empty. No tier key at all means the sender is talking
        // about something else and this is not a snapshot; an empty one is a flat book.
        if (TIER_KEYS.none { (key, _) -> record.containsKey(key) }) return null

        val tiers = HashMap<String, WatchTier>()
        for ((key, tier) in TIER_KEYS) {
            for (symbol in symbols(record[key])) {
                // Listed twice, so take the stronger reading. Ordering TIER_KEYS weakest
                // first would do it too, but relying on declaration order for
                // correctness is the kind of thing that survives exactly one refactor.
                val existing = tiers[symbol]
                if (existing == null || tier.order > existing.order) tiers[symbol] = tier
            }
        }

        return PositionSnapshot(
            // Seconds on the wire, like ntfy's own timestamps and the quote payload's.
            takenAtMillis = record["ts"]?.trim()?.toLongOrNull()?.times(1000),
            tiers = tiers,
            weights = weights(record, tiers.keys),
        )
    }

    /**
     * Weights for symbols the snapshot also places in a tier.
     *
     * A weight for a name that is not in any tier describes a position the same message
     * says you do not hold, so it is dropped rather than stored against nothing.
     */
    private fun weights(record: Map<String, String?>, known: Set<String>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((key, value) in record) {
            if (!key.startsWith(WEIGHT_PREFIX)) continue
            val symbol = key.removePrefix(WEIGHT_PREFIX).trim().uppercase(Locale.US)
            if (symbol.isEmpty() || symbol !in known) continue
            val percent = value?.trim()?.toDoubleOrNull() ?: continue
            if (percent > 0.0) out[symbol] = percent
        }
        return out
    }

    private const val WEIGHT_PREFIX = "w."

    private fun symbols(raw: String?): List<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
}
