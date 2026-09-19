package com.abhinavxt.newsforge.core.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One feed, as it appears in a backup file.
 *
 * Deliberately not [com.abhinavxt.newsforge.data.db.FeedEntity]. A backup is a contract
 * with a file written months ago and possibly by a different version of the app, so it
 * carries only what the reader actually chose — the URL, what to call it, how to parse it,
 * how much to trust it — and nothing the app derived for itself.
 *
 * `builtIn` is absent on purpose. Whether a feed ships with the app is a fact about this
 * build's seed list, not about the person's configuration, and a file claiming a feed is
 * built-in could make a restore create an undeletable row for a feed the app no longer
 * ships.
 *
 * Nothing about polling state travels either — etags, last success, failure counts. Those
 * describe a conversation between one installation and a server, and restoring them would
 * tell a fresh install it had already seen content it has never fetched.
 */
data class FeedRecord(
    val id: String?,
    val name: String,
    val url: String,
    val tier: String,
    val kind: String,
    val categoryHint: String?,
    val enabled: Boolean,
    val position: Int,
)

/** What a restore did, so the screen can say something more useful than "done". */
data class FeedMergeResult(
    /** Rows to write. Existing feeds appear with their original ids. */
    val toSave: List<FeedRecord> = emptyList(),
    val added: Int = 0,
    val updated: Int = 0,
    /** Present in the file, identical to what is already stored. */
    val unchanged: Int = 0,
    /** Unusable rows: no URL, or one that is not http. */
    val skipped: Int = 0,
) {
    val isEmpty: Boolean get() = added == 0 && updated == 0
}

/**
 * Reading and writing the feed list as a file.
 *
 * Feed configuration is the one part of this app that is genuinely expensive to lose. The
 * articles regenerate on the next sync and the watchlist is a dozen tickers you could
 * retype; a feed list is an accumulation of URLs found over months, several of which no
 * longer appear anywhere obvious on the sites that publish them.
 *
 * Kept pure so the merge rules — which are the part with judgement in them — are testable
 * without a device. The JSON and the file picker live in the layers above.
 */
object FeedBackup {

    const val VERSION: Int = 1

    /** Envelope key, which doubles as the marker that this is one of our files. */
    const val VERSION_KEY: String = "newsforge"

    const val FEEDS_KEY: String = "feeds"
    const val EXPORTED_AT_KEY: String = "exportedAt"
    const val MIME_TYPE: String = "application/json"

    fun fileName(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
            .format(Instant.ofEpochMilli(atMillis).atZone(zone))
        return "newsforge-feeds-$day.json"
    }

    fun toRecords(feeds: List<FeedRecord>): List<Map<String, String?>> = feeds.map { feed ->
        buildMap {
            feed.id?.let { put("id", it) }
            put("name", feed.name)
            put("url", feed.url)
            put("tier", feed.tier)
            put("kind", feed.kind)
            feed.categoryHint?.let { put("categoryHint", it) }
            put("enabled", feed.enabled.toString())
            put("position", feed.position.toString())
        }
    }

    /**
     * @param defaults supplies tier and kind for rows that omit them, so a hand-written
     *   file needs only a URL.
     */
    fun fromRecords(
        records: List<Map<String, String?>>,
        defaults: FeedDefaults = FeedDefaults(),
    ): List<FeedRecord> = records.mapNotNull { record ->
        val url = record["url"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
        FeedRecord(
            id = record["id"]?.trim()?.takeIf { it.isNotEmpty() },
            // Falling back to the host rather than refusing the row: a feed with a working
            // URL and no name is worth keeping, and the editor can rename it.
            name = record["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: hostOf(url),
            url = url,
            tier = record["tier"]?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
                ?: defaults.tier,
            kind = record["kind"]?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
                ?: defaults.kind,
            categoryHint = record["categoryHint"]?.trim()?.uppercase(Locale.US)
                ?.takeIf { it.isNotEmpty() },
            // Absent means on. A file listing a feed at all is a statement that it is
            // wanted; only an explicit false turns it off.
            enabled = record["enabled"]?.trim()?.lowercase(Locale.US) != "false",
            position = record["position"]?.trim()?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Folds a restored list into what is already configured.
     *
     * Merge, never replace. A restore must be safe to run on a phone that is already set
     * up: feeds absent from the file are left alone, because the alternative is a file
     * from March silently deleting everything added since. The cost of that choice is
     * that removals do not travel, which is the right way round — a feed you have to
     * delete twice is an annoyance, a feed list you have to rebuild is an evening.
     *
     * Matching is by URL, not by id. The same feed added by hand on two phones has two
     * different ids and is obviously one feed; matching on id would restore it twice.
     *
     * Where a feed already exists the file wins on everything the reader edits — name,
     * tier, kind, hint, enabled — because that is what restoring means. The stored id is
     * kept regardless, so polling state, which is keyed on it, survives.
     *
     * @param newId ids for feeds being created. Supplied rather than generated here so
     *   this stays pure and so the caller decides what an id looks like.
     */
    fun merge(
        existing: List<FeedRecord>,
        restored: List<FeedRecord>,
        newId: (FeedRecord) -> String,
    ): FeedMergeResult {
        val byUrl = existing.associateBy { canonicalUrl(it.url) }
        val toSave = ArrayList<FeedRecord>()
        var added = 0
        var updated = 0
        var unchanged = 0

        // De-duplicated against itself too: a file that lists the same URL twice, which a
        // hand-merged one easily can, must not create the feed and then update it.
        val seen = HashSet<String>()

        for (record in restored) {
            val key = canonicalUrl(record.url)
            if (!seen.add(key)) continue

            val current = byUrl[key]
            if (current == null) {
                toSave += record.copy(id = record.id ?: newId(record))
                added++
                continue
            }
            val merged = current.copy(
                name = record.name,
                tier = record.tier,
                kind = record.kind,
                categoryHint = record.categoryHint,
                enabled = record.enabled,
                position = record.position,
            )
            if (merged == current) {
                unchanged++
            } else {
                toSave += merged
                updated++
            }
        }

        return FeedMergeResult(
            toSave = toSave,
            added = added,
            updated = updated,
            unchanged = unchanged,
            skipped = 0,
        )
    }

    /**
     * Enough normalisation to recognise the same feed written two ways.
     *
     * Case and a trailing slash, and nothing more. Query strings stay: NSE's endpoints
     * differ only by `?index=`, and stripping it would collapse three distinct feeds into
     * one and quietly delete two of them from the merge.
     */
    fun canonicalUrl(url: String): String =
        url.trim().lowercase(Locale.US).trimEnd('/')

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.").ifEmpty { url }
}

/** Fallbacks for rows that leave fields out. */
data class FeedDefaults(
    val tier: String = "AGGREGATOR",
    val kind: String = "RSS",
)
