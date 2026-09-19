package com.abhinavxt.newsforge.core.desk

import java.util.Locale
import kotlin.math.abs

/**
 * A quote, and where it came from.
 *
 * This is the answer to a problem the app has had since the first patch: intraday, a
 * headline without the price reaction beside it is half a signal, and this app has no
 * broker credentials and should not have any. TickerForge does. Once there is a channel
 * between them, the missing half can simply be sent — no Kite on the phone, no second
 * scraper, no duplicated market-data layer.
 *
 * Two sources fill this in. The desk pushes real, licensed, real-time quotes — but only
 * while TickerForge is running. Underneath sits the exchange's own public endpoint, which
 * is delayed by regulation but always there. [kind] says which, and it matters: it is
 * what decides how long the quote stays worth showing.
 *
 * Every field except [symbol] is optional. The source decides how much to include, and a
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
    /**
     * The 52-week extremes, where the source happens to know them.
     *
     * Present on the exchange's index response for free, absent from Kite's quote — so
     * the desk fills these only if it has computed them from history. Optional like
     * everything else here: a payload without them is not a worse payload, it just means
     * the range has to come from stored candles instead.
     */
    val yearHigh: Double? = null,
    val yearLow: Double? = null,
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
    fun isStale(nowMillis: Long, maxAgeMillis: Long = defaultStaleMillis): Boolean {
        val at = timestampMillis ?: return true
        return nowMillis - at > maxAgeMillis
    }

    /**
     * How long this quote stays useful, which depends on where it came from.
     *
     * A desk quote is live, so five minutes old means something went wrong. Exchange web
     * data is delayed by regulation before it is even published, so the same window would
     * mark every one of them stale on arrival and the feed would show no prices at all.
     * The wider window is not a claim that the number is fresher — it is an admission
     * that this source is never fresh and is still worth reading.
     */
    val defaultStaleMillis: Long
        get() = if (kind == KIND_EXCHANGE) EXCHANGE_STALE_MS else DEFAULT_STALE_MS

    /** True when this came from the exchange rather than the desk. */
    val isDelayed: Boolean get() = kind == KIND_EXCHANGE

    companion object {
        const val KIND_QUOTE = "quote"

        /**
         * A message carrying several symbols at once.
         *
         * One quote per ntfy message does not survive contact with a real watchlist:
         * twenty names at a five-minute cadence is 240 messages an hour, which buries
         * every strategy signal in the desk log and pushes the useful ones straight past
         * the scan window the feed reads. A batch is one message a cycle regardless of
         * how many names it covers.
         */
        const val KIND_QUOTE_BATCH = "quotes"

        /** Pulled from the exchange's public endpoint rather than pushed by the desk. */
        const val KIND_EXCHANGE = "nse"

        const val DEFAULT_STALE_MS = 5L * 60 * 1000

        /** Wide enough to survive the delay the exchange applies before publishing. */
        const val EXCHANGE_STALE_MS = 25L * 60 * 1000
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

    /**
     * Payload kinds that share this schema's version marker and are not quotes.
     *
     * An unknown `kind` is still read as a quote, deliberately — the desk can invent one
     * and a payload carrying a price should render whatever it calls itself. But a kind
     * this app already understands as something else must not also be read as a quote,
     * and [Candles] is exactly that: it carries a top-level `symbol`, so without this it
     * decodes into a price-less payload and every batch of bars appears in the desk list
     * as a phantom quote, dashed and permanently stale.
     *
     * Positions and screens never hit this because neither carries a top-level `symbol`;
     * they fall out at the line below. Candles do, which is what made this necessary.
     */
    private val NON_QUOTE_KINDS = setOf(Candles.KIND)

    /**
     * Kinds that exist to be stored, not read.
     *
     * A quote payload is machine-written and still worth showing — it renders as a price
     * next to the message that carried it. A candle batch is not: it is several kilobytes
     * of positional digits whose entire purpose is the chart it ends up drawing, and one
     * glance at a company now produces five of them. Left in the list they bury the
     * signals a person actually subscribed for.
     */
    val MACHINE_KINDS = setOf(Candles.KIND)

    /** True when this payload is data for the app rather than a message for the reader. */
    fun isMachine(record: Map<String, String?>): Boolean =
        isSupported(record) && record["kind"]?.trim()?.lowercase(Locale.US) in MACHINE_KINDS

    fun fromFlat(record: Map<String, String?>): DeskPayload? {
        if (!isSupported(record)) return null
        if (record["kind"]?.trim()?.lowercase(Locale.US) in NON_QUOTE_KINDS) return null
        val symbol = symbolOf(record["symbol"]) ?: return null
        return payloadAt(
            record = record,
            prefix = "",
            symbol = symbol,
            kind = record["kind"]?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
                ?: DeskPayload.KIND_QUOTE,
            fallbackTimestamp = timestampOf(record["ts"]),
        )
    }

    /**
     * Decodes a multi-symbol quote message.
     *
     * Symbols are keys of an object rather than entries of an array:
     *
     * ```json
     * {"nf":1,"kind":"quotes","ts":1789649400,
     *  "q":{"BEL":{"ltp":412.35,"prev_close":405.10},
     *       "INFY":{"ltp":1502.0,"prev_close":1488.5,"day":{"h":1510.0,"l":1486.0}}}}
     * ```
     *
     * Keyed, not listed, for two reasons. The decoder flattens nested objects to dotted
     * paths and deliberately skips arrays — inventing an index encoding the sender never
     * agreed to is how a schema starts lying — so `q.BEL.ltp` needs no new machinery at
     * all. And a map cannot carry the same symbol twice, which an array can, leaving the
     * reader to guess which one won.
     *
     * Each entry accepts exactly the fields a single quote does. `ts` at the top level
     * applies to every symbol; an entry may override it with its own.
     *
     * @return the quotes, or empty when this is not a batch.
     */
    fun batchFromFlat(record: Map<String, String?>): List<DeskPayload> {
        if (!isSupported(record)) return emptyList()
        if (record["kind"]?.trim()?.lowercase(Locale.US) != DeskPayload.KIND_QUOTE_BATCH) {
            return emptyList()
        }

        val sharedTimestamp = timestampOf(record["ts"])
        // Distinct and in first-seen order, so a batch renders the way it was written.
        val keys = LinkedHashSet<String>()
        for (key in record.keys) {
            if (!key.startsWith(BATCH_PREFIX)) continue
            val raw = key.removePrefix(BATCH_PREFIX).substringBefore('.')
            if (raw.isNotEmpty()) keys.add(raw)
        }

        return keys.mapNotNull { raw ->
            val symbol = symbolOf(raw) ?: return@mapNotNull null
            val quote = payloadAt(
                record = record,
                prefix = "$BATCH_PREFIX$raw.",
                symbol = symbol,
                kind = DeskPayload.KIND_QUOTE,
                fallbackTimestamp = sharedTimestamp,
            )
            // A batch entry with no last price is a key that matched nothing — a typo in
            // the sender, or a symbol it had no data for. Dropping it beats rendering a
            // panel of dashes that looks like a failed fetch.
            quote.takeIf { it.ltp != null }
        }
    }

    /** Reads one quote's worth of fields from [prefix] within an already flattened record. */
    private fun payloadAt(
        record: Map<String, String?>,
        prefix: String,
        symbol: String,
        kind: String,
        fallbackTimestamp: Long?,
    ): DeskPayload {
        val levelPrefix = prefix + LEVEL_PREFIX
        val levels = record.entries
            .filter { it.key.startsWith(levelPrefix) }
            .mapNotNull { (key, value) ->
                val name = key.removePrefix(levelPrefix).takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val number = value?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                name to number
            }
            .toMap()

        return DeskPayload(
            symbol = symbol,
            kind = kind,
            timestampMillis = timestampOf(record[prefix + "ts"]) ?: fallbackTimestamp,
            ltp = number(record, prefix + "ltp"),
            previousClose = number(record, prefix + "prev_close"),
            vwap = number(record, prefix + "vwap"),
            dayOpen = number(record, prefix + "day.o"),
            dayHigh = number(record, prefix + "day.h"),
            dayLow = number(record, prefix + "day.l"),
            yearHigh = number(record, prefix + "year.h"),
            yearLow = number(record, prefix + "year.l"),
            volume = record[prefix + "volume"]?.trim()?.toDoubleOrNull()?.toLong(),
            relativeVolume = number(record, prefix + "rel_volume"),
            levels = levels,
            confluenceScore = record[prefix + "confluence.score"]?.trim()?.toDoubleOrNull()?.toInt(),
            confluenceVerdict = record[prefix + "confluence.verdict"]?.trim()
                ?.takeIf { it.isNotEmpty() },
            note = record[prefix + "note"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Version-gated rather than shape-sniffed: a future sender can change the schema and
     * an older app will ignore it instead of rendering it wrongly.
     */
    private fun isSupported(record: Map<String, String?>): Boolean =
        record[VERSION_KEY]?.trim()?.toIntOrNull() == SUPPORTED_VERSION

    private fun symbolOf(raw: String?): String? =
        raw?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }

    /** Seconds on the wire, like ntfy's own timestamps. */
    private fun timestampOf(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.times(1000)

    /** Cheap pre-check so ordinary prose is never handed to a JSON parser. */
    fun looksLikePayload(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"$VERSION_KEY\"")
    }

    private const val LEVEL_PREFIX = "levels."

    /** Where a batch keeps its symbols. Short because it repeats once per name. */
    private const val BATCH_PREFIX = "q."

    private fun number(record: Map<String, String?>, key: String): Double? =
        record[key]?.trim()?.toDoubleOrNull()
}
