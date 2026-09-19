package com.abhinavxt.newsforge.data.net

import com.abhinavxt.newsforge.core.desk.Candles
import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.desk.DeskPayloads
import com.abhinavxt.newsforge.core.desk.NtfyMessages
import com.abhinavxt.newsforge.core.desk.PositionSnapshot
import com.abhinavxt.newsforge.core.desk.PositionSnapshots
import com.abhinavxt.newsforge.core.desk.ScreenResult
import com.abhinavxt.newsforge.core.desk.ScreenResults
import com.abhinavxt.newsforge.core.quote.CandleBatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes an ntfy poll response.
 *
 * The body is newline-delimited JSON, one object per line, not a JSON array — so it cannot
 * be handed to a document parser whole. As with the NSE decoder, everything judgemental
 * lives in the pure layer; this only walks the bytes.
 */
object NtfyJson {

    fun parse(bytes: ByteArray, fallbackMillis: Long): List<DeskMessage> {
        val records = ArrayList<Map<String, String?>>()
        for (line in bytes.toString(Charsets.UTF_8).lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            // One malformed frame must not discard the rest of the batch: a partial write
            // at the tail is normal for a streamed endpoint.
            val row = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
            val map = HashMap<String, String?>(row.length())
            for (key in row.keys()) {
                map[key] = when {
                    row.isNull(key) -> null
                    // tags arrive as an array; flatten so the pure layer sees only strings.
                    row.opt(key) is JSONArray -> (0 until row.getJSONArray(key).length())
                        .joinToString(",") { row.getJSONArray(key).optString(it) }
                    else -> row.get(key).toString()
                }
            }
            records.add(map)
        }
        return NtfyMessages.fromRecords(records, fallbackMillis)
    }

    /**
     * Reads a structured payload out of a message body.
     *
     * Nested objects are flattened to dotted keys — `day.h`, `levels.pdh`,
     * `confluence.score` — so the contract itself stays in the pure layer and testable.
     * Returns null for ordinary prose, which is the overwhelming majority of messages.
     */
    fun parsePayload(body: String): DeskPayload? =
        flatten(body)?.let { DeskPayloads.fromFlat(it) }

    /**
     * Every quote in a message body, whether it carried one or twenty.
     *
     * The single and batch schemas are tried in that order rather than branched on `kind`
     * here, so the shape stays decided in the pure layer where it is tested.
     */
    fun parseQuotes(body: String): List<DeskPayload> {
        val flat = flatten(body) ?: return emptyList()
        DeskPayloads.fromFlat(flat)?.let { return listOf(it) }
        return DeskPayloads.batchFromFlat(flat)
    }

    /** Flattens a payload body, or null when it is ordinary prose. */
    private fun flatten(body: String): Map<String, String?>? {
        if (!DeskPayloads.looksLikePayload(body)) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val flat = HashMap<String, String?>()
        flatten(root, prefix = "", into = flat)
        return flat
    }

    /**
     * Reads an exposure snapshot out of a message body.
     *
     * Separate from [parsePayload] because the two schemas share a version marker and
     * nothing else: a quote is about one symbol and a snapshot is about all of them, so
     * there is no shape that could serve both without one of them carrying dead fields.
     */
    fun parsePositions(body: String): PositionSnapshot? =
        flatten(body)?.let { PositionSnapshots.fromFlat(it) }

    fun parseScreens(body: String): List<ScreenResult> =
        flatten(body)?.let { ScreenResults.fromFlat(it) }.orEmpty()

    /**
     * Reads a run of price bars out of a message body.
     *
     * The bars themselves are one string field rather than a JSON array, because
     * [flatten] skips arrays on purpose — see [Candles] for why that rule is worth
     * keeping and why the string form is the one that fits the message limit anyway.
     */
    fun parseCandles(body: String): CandleBatch? =
        flatten(body)?.let { Candles.fromFlat(it) }

    private fun flatten(obj: JSONObject, prefix: String, into: MutableMap<String, String?>) {
        for (key in obj.keys()) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                obj.isNull(key) -> into[path] = null
                obj.opt(key) is JSONObject -> flatten(obj.getJSONObject(key), path, into)
                // Arrays have no meaning in the payload schema; skipping beats inventing
                // an encoding the sender never agreed to.
                obj.opt(key) is JSONArray -> Unit
                else -> into[path] = obj.get(key).toString()
            }
        }
    }
}
