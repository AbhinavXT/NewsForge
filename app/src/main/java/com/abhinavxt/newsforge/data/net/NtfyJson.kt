package com.abhinavxt.newsforge.data.net

import com.abhinavxt.newsforge.core.desk.DeskMessage
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.desk.DeskPayloads
import com.abhinavxt.newsforge.core.desk.NtfyMessages
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
    fun parsePayload(body: String): DeskPayload? {
        if (!DeskPayloads.looksLikePayload(body)) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val flat = HashMap<String, String?>()
        flatten(root, prefix = "", into = flat)
        return DeskPayloads.fromFlat(flat)
    }

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
