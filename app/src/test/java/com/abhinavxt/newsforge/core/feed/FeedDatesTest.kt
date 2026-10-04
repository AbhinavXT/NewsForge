package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class FeedDatesTest {

    private val expected =
        OffsetDateTime.parse("2026-09-09T14:32:00+05:30").toInstant().toEpochMilli()

    @Test
    fun parsesRfc1123WithNumericOffset() {
        assertEquals(expected, FeedDates.parse("Wed, 09 Sep 2026 14:32:00 +0530"))
    }

    @Test
    fun parsesSingleDigitDay() {
        assertEquals(expected, FeedDates.parse("Wed, 9 Sep 2026 14:32:00 +0530"))
    }

    @Test
    fun parsesAlphabeticZoneRejectedByRfc1123() {
        // DateTimeFormatter.RFC_1123_DATE_TIME throws on "IST"; several Indian feeds use it.
        assertEquals(expected, FeedDates.parse("Wed, 09 Sep 2026 14:32:00 IST"))
    }

    @Test
    fun parsesIso8601() {
        assertEquals(expected, FeedDates.parse("2026-09-09T14:32:00+05:30"))
        assertEquals(expected, FeedDates.parse("2026-09-09T09:02:00Z"))
    }

    @Test
    fun assumesIstForZonelessTimestamps() {
        assertEquals(expected, FeedDates.parse("2026-09-09 14:32:00"))
    }

    @Test
    fun parsesDateOnlyAsStartOfDayIst() {
        val startOfDay =
            OffsetDateTime.parse("2026-09-09T00:00:00+05:30").toInstant().toEpochMilli()
        assertEquals(startOfDay, FeedDates.parse("2026-09-09"))
    }

    @Test
    fun toleratesSurroundingWhitespaceAndDoubleSpaces() {
        assertEquals(expected, FeedDates.parse("  Wed,  09  Sep  2026  14:32:00  +0530 "))
    }

    @Test
    fun returnsNullForUnparseableInput() {
        assertNull(FeedDates.parse(null))
        assertNull(FeedDates.parse(""))
        assertNull(FeedDates.parse("   "))
        assertNull(FeedDates.parse("yesterday evening"))
    }

    @Test
    fun normaliseZoneRewritesTrailingAbbreviation() {
        assertEquals(
            "Wed, 09 Sep 2026 14:32:00 +0530",
            FeedDates.normaliseZone("Wed, 09 Sep 2026 14:32:00 IST"),
        )
        // An already-numeric offset must survive untouched.
        assertEquals(
            "Wed, 09 Sep 2026 14:32:00 +0530",
            FeedDates.normaliseZone("Wed, 09 Sep 2026 14:32:00 +0530"),
        )
    }
}
