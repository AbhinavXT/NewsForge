package com.abhinavxt.newsforge.data.net

import com.abhinavxt.newsforge.core.backup.FeedBackup
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown when a chosen file is not a NewsForge backup, or not one this build understands. */
class BackupFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The envelope around a feed backup.
 *
 * Thin on purpose, like [NseJson]: every decision about what a row means lives in
 * [FeedBackup], which is pure and tested. This file only knows how to walk JSON, which is
 * the part that needs Android's `org.json` on the classpath.
 */
object BackupJson {

    fun encode(records: List<Map<String, String?>>, exportedAtIso: String): String {
        val feeds = JSONArray()
        for (record in records) {
            val row = JSONObject()
            for ((key, value) in record) {
                if (value != null) row.put(key, value)
            }
            feeds.put(row)
        }
        return JSONObject()
            .put(FeedBackup.VERSION_KEY, FeedBackup.VERSION)
            .put(FeedBackup.EXPORTED_AT_KEY, exportedAtIso)
            .put(FeedBackup.FEEDS_KEY, feeds)
            // Indented, because a backup that a person can open and read is one they can
            // also fix by hand when a URL has rotted, and that is half the value of
            // having the file at all.
            .toString(2)
    }

    /**
     * @throws BackupFormatException when the file is not ours, or is from a newer build.
     *   Refusing beats guessing: a forward-compatible reader that ignores fields it does
     *   not know would restore a subset of the file and report success.
     */
    fun decode(text: String): List<Map<String, String?>> {
        val root = try {
            JSONObject(text.trim())
        } catch (e: JSONException) {
            throw BackupFormatException("Not a NewsForge backup — this file is not JSON", e)
        }

        val version = root.optInt(FeedBackup.VERSION_KEY, -1)
        if (version < 0) {
            throw BackupFormatException("Not a NewsForge backup — no version marker")
        }
        if (version > FeedBackup.VERSION) {
            throw BackupFormatException(
                "Backup is from a newer version of NewsForge (format $version)"
            )
        }

        val feeds = root.optJSONArray(FeedBackup.FEEDS_KEY)
            ?: throw BackupFormatException("Backup has no feeds in it")

        val rows = ArrayList<Map<String, String?>>(feeds.length())
        for (i in 0 until feeds.length()) {
            val row = feeds.optJSONObject(i) ?: continue
            val map = HashMap<String, String?>(row.length())
            for (key in row.keys()) {
                // Flattened to strings, as everywhere else: a hand-edited file will have
                // `"enabled": "false"` as often as `false`, and both should work.
                map[key] = if (row.isNull(key)) null else row.get(key).toString()
            }
            rows.add(map)
        }
        return rows
    }
}
