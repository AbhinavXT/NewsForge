package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class VolumeProfileTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    private fun at(text: String): Long =
        ZonedDateTime.parse("$text+05:30[Asia/Kolkata]").toInstant().toEpochMilli()

    @Test
    fun marksAreCountedFromTheOpen() {
        // Anchored to the session rather than to midnight, so the same mark means the
        // same thing on every day.
        assertEquals(0, VolumeProfile.bucketOf(at("2026-09-17T09:15:00"), ist))
        assertEquals(0, VolumeProfile.bucketOf(at("2026-09-17T09:29:59"), ist))
        assertEquals(1, VolumeProfile.bucketOf(at("2026-09-17T09:30:00"), ist))
        assertEquals(24, VolumeProfile.bucketOf(at("2026-09-17T15:15:00"), ist))
    }

    @Test
    fun outsideTheSessionThereIsNoMark() {
        // The question has no answer at 20:00, and answering anyway would publish this
        // morning's ratios into this evening's feed.
        assertNull(VolumeProfile.bucketOf(at("2026-09-17T08:00:00"), ist))
        assertNull(VolumeProfile.bucketOf(at("2026-09-17T20:00:00"), ist))
    }

    @Test
    fun aThinHistorySaysNothing() {
        // Two prior days is not a baseline. One holiday-thin Friday would double every
        // ratio the following Monday.
        assertNull(VolumeProfile.relative(1_000.0, listOf(500.0, 400.0)))
        assertNull(VolumeProfile.relative(1_000.0, emptyList()))
    }

    @Test
    fun aRatioIsTheMedianOfPriorSessionsAtTheSameMark() {
        val ratio = VolumeProfile.relative(
            today = 3_000.0,
            priorSessions = listOf(1_000.0, 900.0, 1_100.0, 1_000.0, 1_000.0),
        )
        assertEquals(3.0, ratio ?: 0.0, 1e-9)
    }

    @Test
    fun oneOutlierSessionDoesNotHideABusyDay() {
        // The reason for a median. A results day in the sample would drag an average far
        // enough to make the next genuinely heavy session look ordinary.
        val priors = listOf(1_000.0, 1_000.0, 1_000.0, 1_000.0, 20_000.0)
        val ratio = VolumeProfile.relative(today = 3_000.0, priorSessions = priors)
        assertEquals(3.0, ratio ?: 0.0, 1e-9)
    }

    @Test
    fun sessionsWithNoVolumeAreNotCountedAsHistory() {
        // A zero is a symbol that did not trade, not a quiet day worth averaging in.
        assertNull(
            VolumeProfile.relative(1_000.0, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 900.0))
        )
    }

    @Test
    fun ordinaryVolumeIsNotWorthSaying() {
        // Most rows, most of the time. A badge that appears on every row stops being read.
        assertEquals(VolumeLevel.ORDINARY, VolumeProfile.levelOf(null))
        assertEquals(VolumeLevel.ORDINARY, VolumeProfile.levelOf(1.0))
        assertEquals(VolumeLevel.ORDINARY, VolumeProfile.levelOf(1.7))
    }

    @Test
    fun busyAndVeryBusyAreDistinguished() {
        assertEquals(VolumeLevel.ACTIVE, VolumeProfile.levelOf(VolumeProfile.ACTIVE_RATIO))
        assertEquals(VolumeLevel.ACTIVE, VolumeProfile.levelOf(2.9))
        assertEquals(VolumeLevel.HEAVY, VolumeProfile.levelOf(VolumeProfile.HEAVY_RATIO))
        assertEquals(VolumeLevel.HEAVY, VolumeProfile.levelOf(12.0))
    }

    @Test
    fun sessionsGroupByLocalDay() {
        val morning = VolumeProfile.sessionDay(at("2026-09-17T09:20:00"), ist)
        val afternoon = VolumeProfile.sessionDay(at("2026-09-17T15:20:00"), ist)
        assertEquals(morning, afternoon)
        assertTrue(VolumeProfile.sessionDay(at("2026-09-18T09:20:00"), ist) == morning + 1)
    }

    @Test
    fun theLabelIsFixedWidthUnderTheMonoFace() {
        assertEquals("3.2x vol", VolumeProfile.label(3.24))
        assertEquals("12.0x vol", VolumeProfile.label(12.0))
    }
}
