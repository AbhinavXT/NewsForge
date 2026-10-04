package com.abhinavxt.newsforge.data.net

import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.feed.FeedParseException
import com.abhinavxt.newsforge.core.feed.NseFilings
import com.abhinavxt.newsforge.core.model.ParsedItem
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns an NSE JSON response into the string maps [NseFilings] works on.
 *
 * Kept deliberately thin. All the judgement — which key to prefer, what counts as
 * material, how a filing gets its identity — lives in `NseFilings`, which is pure and
 * covered by tests. This file only knows how to walk JSON, which is the part that cannot
 * be exercised without the Android `org.json` on the classpath.
 */
object NseJson {

    /** Keys an NSE payload has been seen to wrap its rows in. */
    private val ENVELOPE_KEYS = listOf("data", "rows", "records", "value")

    /**
     * Decodes to raw rows.
     *
     * Exposed separately because one response feeds two consumers — the article parser
     * and the calendar extractor — and fetching or decoding it twice would be waste.
     */
    fun rows(bytes: ByteArray): List<Map<String, String?>> {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) throw FeedParseException("Empty response")
        return try {
            rowsOf(text)
        } catch (e: JSONException) {
            throw FeedParseException("Not valid JSON: ${e.message}", e)
        }
    }

    fun parse(kind: FeedKind, bytes: ByteArray): List<ParsedItem> =
        NseFilings.parse(kind, rows(bytes))

    private fun rowsOf(text: String): List<Map<String, String?>> = when (text.first()) {
        '[' -> toRows(JSONArray(text))
        '{' -> {
            val root = JSONObject(text)
            val key = ENVELOPE_KEYS.firstOrNull { root.optJSONArray(it) != null }
                ?: throw FeedParseException(
                    "JSON object with no rows array (looked for ${ENVELOPE_KEYS.joinToString()})"
                )
            toRows(root.getJSONArray(key))
        }
        // An HTML error page served with a 200 is the usual shape of a blocked request.
        else -> throw FeedParseException("Response is not JSON")
    }

    private fun toRows(array: JSONArray): List<Map<String, String?>> {
        val rows = ArrayList<Map<String, String?>>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val map = HashMap<String, String?>(row.length())
            for (key in row.keys()) {
                // Everything is flattened to a string: NseFilings does its own parsing,
                // and NSE mixes types for the same field across responses.
                map[key] = if (row.isNull(key)) null else row.get(key).toString()
            }
            rows.add(map)
        }
        return rows
    }
}
