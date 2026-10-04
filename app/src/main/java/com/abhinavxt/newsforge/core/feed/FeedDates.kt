package com.abhinavxt.newsforge.core.feed

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses the date formats that show up in real RSS and Atom feeds.
 *
 * The spec says RSS uses RFC-822 and Atom uses RFC-3339, and in practice neither is
 * reliably true: Indian publisher feeds emit bare local timestamps, alphabetic zone
 * abbreviations that `DateTimeFormatter.RFC_1123_DATE_TIME` rejects, and occasionally a
 * date with no time at all. A feed whose dates all fail to parse silently sorts to the
 * bottom of the app forever, so this is worth being generous about.
 */
object FeedDates {

    /**
     * Fallback zone for timestamps that carry no offset.
     *
     * Every publisher this app polls is Indian or reports for an Indian audience, so
     * assuming IST is far better than assuming UTC — the latter would date a 09:20
     * headline as 14:50 and park it at the top of the feed for five hours.
     */
    val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * Alphabetic zone names, mapped to fixed offsets.
     *
     * "IST" is genuinely ambiguous (India, Israel, Ireland). Resolving it to +05:30 is a
     * deliberate choice for this app's source list, not an oversight.
     */
    private val ZONE_ABBREVIATIONS = mapOf(
        "IST" to "+0530",
        "UT" to "+0000",
        "UTC" to "+0000",
        "GMT" to "+0000",
        "Z" to "+0000",
        "EST" to "-0500",
        "EDT" to "-0400",
        "CST" to "-0600",
        "CDT" to "-0500",
        "MST" to "-0700",
        "MDT" to "-0600",
        "PST" to "-0800",
        "PDT" to "-0700",
        "BST" to "+0100",
        "CET" to "+0100",
        "CEST" to "+0200",
        "JST" to "+0900",
        "SGT" to "+0800",
        "HKT" to "+0800",
        "AEST" to "+1000",
    )

    private val ZONED_PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ssZ",
    ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

    private val LOCAL_PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss",
        "EEE, d MMM yyyy HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "MMM d, yyyy HH:mm:ss",
        "MMM d, yyyy h:mm a",
        "d MMM yyyy HH:mm:ss",
        // NSE JSON feeds: "05-Sep-2026 14:32:10".
        "dd-MMM-yyyy HH:mm:ss",
        "dd-MMM-yyyy HH:mm",
        "d-MMM-yyyy HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

    private val DATE_ONLY_PATTERNS = listOf(
        "yyyy-MM-dd",
        "d MMM yyyy",
        "MMM d, yyyy",
        "dd-MMM-yyyy",
        "d-MMM-yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

    /**
     * @return epoch millis, or null if nothing recognised the input. Callers should fall
     *   back to fetch time rather than dropping the item.
     */
    fun parse(raw: String?, zone: ZoneId = DEFAULT_ZONE): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // ISO-8601 first: it is both the most precise and the cheapest to reject.
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        }

        val normalised = normaliseZone(text)
        for (format in ZONED_PATTERNS) {
            runCatching {
                return ZonedDateTime.parse(normalised, format).toInstant().toEpochMilli()
            }
        }
        for (format in LOCAL_PATTERNS) {
            runCatching {
                return LocalDateTime.parse(normalised, format)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }
        for (format in DATE_ONLY_PATTERNS) {
            runCatching {
                return java.time.LocalDate.parse(normalised, format)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * Rewrites a trailing alphabetic zone name into a numeric offset and squeezes runs of
     * whitespace, so the pattern list below only has to deal with `Z`-style offsets.
     */
    internal fun normaliseZone(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim().removeSuffix(",")
        val lastSpace = collapsed.lastIndexOf(' ')
        if (lastSpace < 0) return collapsed
        val tail = collapsed.substring(lastSpace + 1)
        val offset = ZONE_ABBREVIATIONS[tail.uppercase(Locale.US)] ?: return collapsed
        return collapsed.substring(0, lastSpace) + " " + offset
    }
}
