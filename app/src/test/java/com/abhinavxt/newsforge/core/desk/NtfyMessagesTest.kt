package com.abhinavxt.newsforge.core.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyMessagesTest {

    private val fallback = 1_757_400_000_000L

    private fun record(vararg pairs: Pair<String, String?>) =
        NtfyMessages.fromRecord(pairs.toMap(), fallback)

    @Test
    fun mapsAFullMessage() {
        val message = record(
            "id" to "abc123",
            "event" to "message",
            "topic" to "tickerforge",
            "title" to "TATASTEEL — alert",
            "message" to "Crossed PDH at 148.20",
            "priority" to "4",
            "tags" to "chart,warning",
            "click" to "https://example.com/desk",
            "time" to "1757400000",
        )!!
        assertEquals("abc123", message.id)
        assertEquals("Crossed PDH at 148.20", message.body)
        assertEquals(listOf("chart", "warning"), message.tags)
        assertTrue(message.isHighPriority)
        assertEquals(1_757_400_000_000L, message.receivedAtMillis)
    }

    @Test
    fun secondsAreConvertedToMillis() {
        // ntfy reports seconds; taking the number as-is would date every message to 1970.
        val message = record("id" to "a", "message" to "x", "time" to "1757400000")!!
        assertEquals(1_757_400_000_000L, message.receivedAtMillis)
    }

    @Test
    fun aMissingTimestampFallsBackToNow() {
        assertEquals(fallback, record("id" to "a", "message" to "x")!!.receivedAtMillis)
    }

    @Test
    fun controlFramesAreDropped() {
        // keepalive arrives every 45 seconds; storing it would fill the list with blanks.
        assertNull(record("id" to "a", "event" to "keepalive"))
        assertNull(record("id" to "a", "event" to "open"))
        assertNull(record("id" to "a", "event" to "poll_request"))
    }

    @Test
    fun aMessageEventIsAssumedWhenTheFieldIsAbsent() {
        assertTrue(record("id" to "a", "message" to "x") != null)
    }

    @Test
    fun framesWithNoContentOrNoIdAreDropped() {
        assertNull(record("id" to "a", "event" to "message"))
        assertNull(record("message" to "orphan"))
    }

    @Test
    fun aTitleAloneIsEnough() {
        val message = record("id" to "a", "title" to "Session heartbeat")!!
        assertEquals("Session heartbeat", message.summary)
        assertEquals("", message.body)
    }

    @Test
    fun priorityDefaultsToNtfysOwnDefaultAndIsClamped() {
        assertEquals(3, record("id" to "a", "message" to "x")!!.priority)
        assertEquals(3, record("id" to "a", "message" to "x", "priority" to "nonsense")!!.priority)
        assertEquals(5, record("id" to "a", "message" to "x", "priority" to "9")!!.priority)
        assertEquals(1, record("id" to "a", "message" to "x", "priority" to "0")!!.priority)
        assertFalse(record("id" to "a", "message" to "x", "priority" to "3")!!.isHighPriority)
    }

    @Test
    fun nonHttpClickTargetsAreIgnored() {
        assertNull(record("id" to "a", "message" to "x", "click" to "javascript:void(0)")!!.clickUrl)
    }

    @Test
    fun summaryPrefersTheTitleThenTheFirstNonBlankLine() {
        assertEquals(
            "Alert",
            record("id" to "a", "title" to "Alert", "message" to "line one\nline two")!!.summary,
        )
        assertEquals(
            "line one",
            record("id" to "a", "message" to "\n\nline one\nline two")!!.summary,
        )
    }

    @Test
    fun pollUrlAsksForQueuedMessagesOnly() {
        val url = NtfyMessages.pollUrl("https://ntfy.sh", "tickerforge", 1_757_400_000L)
        assertEquals("https://ntfy.sh/tickerforge/json?poll=1&since=1757400000", url)
    }

    @Test
    fun pollUrlToleratesUntidyConfigurationAndNoWatermark() {
        assertEquals(
            "https://ntfy.example.com/mytopic/json?poll=1&since=12h",
            NtfyMessages.pollUrl("https://ntfy.example.com/ ", " mytopic ", null),
        )
        // An empty server falls back rather than producing a URL that cannot resolve.
        assertTrue(NtfyMessages.pollUrl("", "t", null).startsWith("https://ntfy.sh/"))
    }

    @Test
    fun emptyInputProducesNoMessages() {
        assertTrue(NtfyMessages.fromRecords(emptyList(), fallback).isEmpty())
    }
}
