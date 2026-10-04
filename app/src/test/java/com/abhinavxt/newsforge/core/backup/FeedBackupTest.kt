package com.abhinavxt.newsforge.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class FeedBackupTest {

    private fun feed(
        id: String? = null,
        url: String,
        name: String = "Feed",
        tier: String = "WIRE",
        kind: String = "RSS",
        enabled: Boolean = true,
        position: Int = 0,
    ) = FeedRecord(id, name, url, tier, kind, null, enabled, position)

    private fun ids(prefix: String = "new"): (FeedRecord) -> String {
        var n = 0
        return { "$prefix-${n++}" }
    }

    @Test
    fun aRoundTripKeepsWhatTheReaderChose() {
        val feeds = listOf(
            feed(id = "a", url = "https://example.com/rss", name = "Example", tier = "WIRE"),
            feed(id = "b", url = "https://nse.example/api", kind = "NSE_ANN", enabled = false),
        )
        assertEquals(feeds, FeedBackup.fromRecords(FeedBackup.toRecords(feeds)))
    }

    @Test
    fun aRowNeedsOnlyAUrl() {
        // The file is meant to be editable by hand when a URL rots; demanding a full row
        // would make that harder than opening the app.
        val record = FeedBackup.fromRecords(listOf(mapOf("url" to "https://www.example.com/feed")))
        assertEquals(1, record.size)
        assertEquals("example.com", record.single().name)
        assertEquals("RSS", record.single().kind)
        assertTrue(record.single().enabled)
    }

    @Test
    fun rowsWithoutAUsableUrlAreDropped() {
        val records = FeedBackup.fromRecords(
            listOf(
                mapOf("name" to "No url"),
                mapOf("url" to "  "),
                mapOf("url" to "ftp://example.com/feed"),
                mapOf("url" to "javascript:alert(1)"),
            )
        )
        assertTrue(records.isEmpty())
    }

    @Test
    fun onlyAnExplicitFalseDisablesAFeed() {
        // A file listing a feed at all is a statement that it is wanted.
        val records = FeedBackup.fromRecords(
            listOf(
                mapOf("url" to "https://a.example/f"),
                mapOf("url" to "https://b.example/f", "enabled" to "false"),
                mapOf("url" to "https://c.example/f", "enabled" to "true"),
            )
        )
        assertEquals(listOf(true, false, true), records.map { it.enabled })
    }

    @Test
    fun restoringNeverDeletesWhatIsAlreadyThere() {
        // The whole safety of running this on a phone that is already set up. A file from
        // March must not silently remove everything added since.
        val existing = listOf(feed(id = "keep", url = "https://kept.example/f"))
        val result = FeedBackup.merge(
            existing = existing,
            restored = listOf(feed(url = "https://new.example/f")),
            newId = ids(),
        )
        assertEquals(1, result.added)
        assertEquals(0, result.updated)
        assertEquals(listOf("https://new.example/f"), result.toSave.map { it.url })
    }

    @Test
    fun anExistingFeedKeepsItsIdSoItsHistorySurvives() {
        // Polling state is keyed on the id. A restore that re-created the row would tell
        // the app it had never fetched a feed it has been reading for months.
        val result = FeedBackup.merge(
            existing = listOf(feed(id = "stored", url = "https://a.example/f", name = "Old")),
            restored = listOf(feed(id = "from-file", url = "https://a.example/f", name = "New")),
            newId = ids(),
        )
        val saved = result.toSave.single()
        assertEquals("stored", saved.id)
        assertEquals("New", saved.name)
        assertEquals(1, result.updated)
        assertEquals(0, result.added)
    }

    @Test
    fun matchingIsByUrlNotById() {
        // The same feed added by hand on two phones has two ids and is one feed.
        val result = FeedBackup.merge(
            existing = listOf(feed(id = "phone-a", url = "https://a.example/f")),
            restored = listOf(feed(id = "phone-b", url = "HTTPS://A.EXAMPLE/f/")),
            newId = ids(),
        )
        assertEquals(0, result.added)
    }

    @Test
    fun aQueryStringIsPartOfTheIdentity() {
        // NSE's endpoints differ only by ?index=. Normalising it away would collapse
        // three feeds into one and drop two of them from the merge.
        val result = FeedBackup.merge(
            existing = listOf(feed(id = "one", url = "https://nse.example/api?index=a")),
            restored = listOf(feed(url = "https://nse.example/api?index=b")),
            newId = ids(),
        )
        assertEquals(1, result.added)
    }

    @Test
    fun anIdenticalFeedIsReportedRatherThanRewritten() {
        val stored = feed(id = "same", url = "https://a.example/f", name = "Same")
        val result = FeedBackup.merge(
            existing = listOf(stored),
            restored = listOf(stored.copy(id = null)),
            newId = ids(),
        )
        assertTrue(result.toSave.isEmpty())
        assertEquals(1, result.unchanged)
        assertTrue(result.isEmpty)
    }

    @Test
    fun aFileListingTheSameUrlTwiceCreatesOneFeed() {
        // Hand-merged files do this. Creating the feed and then updating it would report
        // two changes for one row.
        val result = FeedBackup.merge(
            existing = emptyList(),
            restored = listOf(
                feed(url = "https://a.example/f", name = "First"),
                feed(url = "https://a.example/f/", name = "Second"),
            ),
            newId = ids(),
        )
        assertEquals(1, result.added)
        assertEquals("First", result.toSave.single().name)
    }

    @Test
    fun newFeedsGetAnIdFromTheCaller() {
        val result = FeedBackup.merge(
            existing = emptyList(),
            restored = listOf(feed(url = "https://a.example/f")),
            newId = { "generated" },
        )
        assertEquals("generated", result.toSave.single().id)
    }

    @Test
    fun theFileNameCarriesTheDay() {
        val at = java.time.ZonedDateTime.parse("2026-09-17T19:20:00+05:30").toInstant().toEpochMilli()
        assertEquals(
            "newsforge-feeds-2026-09-17.json",
            FeedBackup.fileName(at, ZoneId.of("Asia/Kolkata")),
        )
    }

    @Test
    fun anIdIsOptionalInTheFile() {
        assertNull(FeedBackup.fromRecords(listOf(mapOf("url" to "https://a.example/f"))).single().id)
    }
}
