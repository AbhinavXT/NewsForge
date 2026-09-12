package com.abhinavxt.newsforge.core.desk

import java.util.Locale
import kotlin.math.abs

/**
 * Market data pushed from your own machine.
 *
 * This is the answer to a problem the app has had since the first patch: intraday, a
 * headline without the price reaction beside it is half a signal, and this app has no
 * broker credentials and should not have any. TickerForge does. Once there is a channel
 * between them, the missing half can simply be sent — no Kite on the phone, no second
 * scraper, no duplicated market-data layer.
 *
 * Every field except [symbol] is optional. The sender decides how much to include, and a
 * payload carrying only a last price is still worth rendering.
 */
data class DeskPayload(
    val symbol: String,
    val kind: String,
    val timestampMillis: Long?,
    val ltp: Double? = null,
    val previousClose: Double? = null,
    val vwap: Double? = null,
    val dayOpen: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val volume: Long? = null,
    /** Volume against its own recent average, as a multiple. */
    val relativeVolume: Double? = null,
    /** Named price levels: pdh, pdl, cpr_tc, cpr_bc, r1, s1 and so on. */
    val levels: Map<String, Double> = emptyMap(),
    val confluenceScore: Int? = null,
    val confluenceVerdict: String? = null,
    val note: String? = null,
) {

    val changePercent: Double?
        get() {
            val last = ltp ?: return null
            val base = previousClose ?: return null
            if (base == 0.0) return null
            return (last - base) / base * 100.0
        }

    /** True when the last price is above VWAP — the usual intraday bias read. */
    val aboveVwap: Boolean?
        get() {
            val last = ltp ?: return null
            val average = vwap ?: return null
            return last > average
        }

    /**
     * Where the last price sits in the day's range, 0 at the low and 1 at the high.
     *
     * Null when the range has no width — a stock at circuit or untraded would otherwise
     * divide by zero and render as a bar pinned at one end, which reads as information
     * when it is the absence of it.
     */
    val positionInDayRange: Double?
        get() {
            val last = ltp ?: return null
            val high = dayHigh ?: return null
            val low = dayLow ?: return null
            if (abs(high - low) < 1e-9) return null
            return ((last - low) / (high - low)).coerceIn(0.0, 1.0)
        }

    /**
     * Quotes go stale fast, and a stale one shown as live is worse than none.
     *
     * A price from twenty minutes ago looks exactly like a current one on screen, and
     * acting on it is precisely the mistake this feature is meant to prevent.
     */
    fun isStale(nowMillis: Long, maxAgeMillis: Long = DEFAULT_STALE_MS): Boolean {
        val at = timestampMillis ?: return true
        return nowMillis - at > maxAgeMillis
    }

    companion object {
        const val KIND_QUOTE = "quote"
        const val DEFAULT_STALE_MS = 5L * 60 * 1000
    }
}

/**
 * Reads payloads out of an ntfy message body.
 *
 * Operates on a flattened string map — nested objects arrive as dotted keys — so the whole
 * contract is testable without a JSON parser on the classpath, which is where the
 * judgement lives anyway.
 */
object DeskPayloads {

    /** Schema version marker. Its presence is what distinguishes a payload from prose. */
    const val VERSION_KEY = "nf"
    const val SUPPORTED_VERSION = 1

    fun fromFlat(record: Map<String, String?>): DeskPayload? {
        // Version-gated rather than shape-sniffed: a future sender can change the schema
        // and an older app will ignore it instead of rendering it wrongly.
        val version = record[VERSION_KEY]?.trim()?.toIntOrNull() ?: return null
        if (version != SUPPORTED_VERSION) return null

        val symbol = record["symbol"]?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: return null

        val levels = record.entries
            .filter { it.key.startsWith(LEVEL_PREFIX) }
            .mapNotNull { (key, value) ->
                val name = key.removePrefix(LEVEL_PREFIX).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val number = value?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                name to number
            }
            .toMap()

        return DeskPayload(
            symbol = symbol,
            kind = record["kind"]?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
                ?: DeskPayload.KIND_QUOTE,
            // Seconds on the wire, like ntfy's own timestamps.
            timestampMillis = record["ts"]?.trim()?.toLongOrNull()?.times(1000),
            ltp = number(record, "ltp"),
            previousClose = number(record, "prev_close"),
            vwap = number(record, "vwap"),
            dayOpen = number(record, "day.o"),
            dayHigh = number(record, "day.h"),
            dayLow = number(record, "day.l"),
            volume = record["volume"]?.trim()?.toDoubleOrNull()?.toLong(),
            relativeVolume = number(record, "rel_volume"),
            levels = levels,
            confluenceScore = record["confluence.score"]?.trim()?.toDoubleOrNull()?.toInt(),
            confluenceVerdict = record["confluence.verdict"]?.trim()?.takeIf { it.isNotEmpty() },
            note = record["note"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Cheap pre-check so ordinary prose is never handed to a JSON parser. */
    fun looksLikePayload(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"$VERSION_KEY\"")
    }

    private const val LEVEL_PREFIX = "levels."

    private fun number(record: Map<String, String?>, key: String): Double? =
        record[key]?.trim()?.toDoubleOrNull()
}
