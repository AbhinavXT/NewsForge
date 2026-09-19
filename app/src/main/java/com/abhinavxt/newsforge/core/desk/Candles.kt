package com.abhinavxt.newsforge.core.desk

import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.core.quote.CandleBatch
import com.abhinavxt.newsforge.core.quote.CandleInterval
import java.util.Locale

/**
 * Reads a run of price bars out of a desk message.
 *
 * ## Why the bars are a string and not an array
 *
 * [com.abhinavxt.newsforge.data.net.NtfyJson] flattens a payload to dotted keys and skips
 * JSON arrays outright, on the grounds that inventing an index encoding the sender never
 * agreed to is how a schema starts lying. That rule is worth keeping, and it rules out the
 * obvious shape. So the bars travel as one string field the sender writes on purpose —
 * which also happens to be the only shape that fits.
 *
 * ntfy caps a message at four kilobytes unless the server is configured otherwise, and
 * two hundred bars as JSON objects is roughly twice that. Positional text with no keys,
 * no quotes and no braces holds between seventy and ninety-five daily bars in the same
 * space — the spread is how wide the prices are, ninety-five for a three-digit price and
 * seventy-four for a six-digit one. Seventy is the batch size that holds for anything on
 * the exchange, and is what the desk should send per message.
 *
 * ## The format
 *
 * ```json
 * {"nf":1,"kind":"candles","symbol":"GRSE","interval":"day","ts":1789649400,
 *  "t0":1758844800,
 *  "bars":"0,412.35,418,410.1,416.75,1234567;86400,416.8,421.5,415,420.2,987654"}
 * ```
 *
 * `bars` is rows separated by `;`, fields separated by `,`:
 * `secondsFromPreviousBar,open,high,low,close,volume`. The first row's offset is measured
 * from `t0`, every later one from the bar before it — so a weekend is one larger number
 * rather than a gap the reader has to infer, and the common case costs five digits.
 *
 * ## Why there is no chunking
 *
 * A long history is sent as several ordinary messages, each complete in itself. Nothing
 * reassembles them, because the database is already the accumulator: bars are keyed by
 * symbol, size and open time, so a message is an upsert and arriving twice is harmless.
 * Sequence numbers would add a partial-delivery state to protect something that cannot be
 * partially delivered — and the same property is what lets the desk send a single bar to
 * correct the one still forming.
 */
object Candles {

    const val KIND = "candles"

    /** Fields per row, in order. */
    private const val FIELDS = 6

    /**
     * Rows accepted from one message.
     *
     * Not a size limit — the message limit already is one — but a guard on the parse
     * itself. A sender that loses a delimiter turns the whole payload into one row, and a
     * malformed field into hundreds of thousands of them; neither should be allowed to
     * spend a phone's memory before failing.
     */
    private const val MAX_ROWS = 2_000

    /**
     * @return the bars in this message, or null when it is not a candle payload.
     *
     *   Null rather than an empty batch for the not-mine case, so a caller can tell "this
     *   message was about something else" from "the desk had no history for that symbol",
     *   which are different answers to show a reader waiting on a chart.
     */
    fun fromFlat(record: Map<String, String?>): CandleBatch? {
        if (record[DeskPayloads.VERSION_KEY]?.trim()?.toIntOrNull() != DeskPayloads.SUPPORTED_VERSION) {
            return null
        }
        if (record["kind"]?.trim()?.lowercase(Locale.US) != KIND) return null

        val symbol = record["symbol"]?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: return null
        val interval = CandleInterval.parse(record["interval"]) ?: return null
        // Seconds on the wire, like every other timestamp in this schema.
        val startSeconds = record["t0"]?.trim()?.toLongOrNull() ?: return null
        val rows = record["bars"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val candles = ArrayList<Candle>()
        var atSeconds = startSeconds
        for ((index, row) in rows.splitToSequence(';').withIndex()) {
            if (index >= MAX_ROWS) break
            val text = row.trim()
            if (text.isEmpty()) continue
            val parts = text.split(',')
            if (parts.size < FIELDS) continue

            val delta = parts[0].trim().toLongOrNull() ?: continue
            val open = parts[1].trim().toDoubleOrNull() ?: continue
            val high = parts[2].trim().toDoubleOrNull() ?: continue
            val low = parts[3].trim().toDoubleOrNull() ?: continue
            val close = parts[4].trim().toDoubleOrNull() ?: continue
            val volume = parts[5].trim().toDoubleOrNull() ?: continue

            // Advanced even when the row is otherwise rejected above would desynchronise
            // every bar after it, so the cursor moves only on a row that is kept.
            atSeconds += delta
            // A bar whose high is below its low is not a bar. Dropping it beats drawing a
            // candle inside out, and beats trusting it into a 52-week extreme.
            if (high < low) continue
            candles += Candle(
                openTimeMillis = atSeconds * 1000,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
            )
        }
        if (candles.isEmpty()) return null
        // Sorted rather than trusted. The database keys on open time and the indicators
        // all assume chronological order, and a sender that pages backwards is a
        // reasonable thing for it to do.
        return CandleBatch(symbol, interval, candles.sortedBy { it.openTimeMillis })
    }

    /**
     * The line that asks for a history.
     *
     * Built here, next to the reply format, so the two cannot drift apart unnoticed. The
     * desk owns the vocabulary — see [com.abhinavxt.newsforge.data.DeskRepository.send] —
     * so this is a suggestion the desk is free to reject, not a contract.
     */
    fun request(symbol: String, interval: CandleInterval, bars: Int): String =
        "/candles ${symbol.trim().uppercase(Locale.US)} ${interval.wireName} $bars"
}
