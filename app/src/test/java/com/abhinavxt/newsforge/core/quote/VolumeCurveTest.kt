package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeCurveTest {

    private val today = 20_000L

    private fun session(day: Long, vararg volumes: Double): List<VolumeSample> =
        volumes.mapIndexed { bucket, volume -> VolumeSample("BEL", day, bucket, volume) }

    /** Five prior sessions plus today, three marks each. */
    private fun sample(
        priorSessions: Int = 5,
        priorShape: List<Double> = listOf(100.0, 200.0, 300.0),
        todayShape: List<Double> = listOf(150.0, 320.0),
    ): List<VolumeSample> =
        (1..priorSessions).flatMap { session(today - it, *priorShape.toDoubleArray()) } +
            session(today, *todayShape.toDoubleArray())

    @Test
    fun theTypicalSeriesIsAMedianPerMark() {
        val curve = VolumeProfile.curve(sample(), today)!!
        assertEquals(100.0, curve.typical[0]!!, 1e-9)
        assertEquals(200.0, curve.typical[1]!!, 1e-9)
        assertEquals(300.0, curve.typical[2]!!, 1e-9)
    }

    /**
     * Per mark, not per day — which is the whole point.
     *
     * One session that was quiet all morning and enormous at the close must not lift the
     * morning marks; a curve that let it would be a daily average wearing a shape.
     */
    @Test
    fun anOddSessionDoesNotLiftTheMarksItWasNormalAt() {
        val samples = sample() + session(today - 9, 100.0, 200.0, 9_000.0)
        val curve = VolumeProfile.curve(samples, today)!!
        assertEquals(100.0, curve.typical[0]!!, 1e-9)
        assertEquals(200.0, curve.typical[1]!!, 1e-9)
        // The median of six values absorbs the outlier at the mark it actually occurred.
        assertEquals(300.0, curve.typical[2]!!, 1e-9)
    }

    /** Today stops where the session has got to, so the chart can stop the line there. */
    @Test
    fun todayIsNullPastTheMarkReached() {
        val curve = VolumeProfile.curve(sample(), today)!!
        assertEquals(150.0, curve.today[0]!!, 1e-9)
        assertEquals(320.0, curve.today[1]!!, 1e-9)
        assertNull(curve.today[2])
    }

    @Test
    fun bothSeriesSpanTheWholeSession() {
        val curve = VolumeProfile.curve(sample(), today)!!
        assertEquals(VolumeProfile.BUCKETS, curve.today.size)
        assertEquals(VolumeProfile.BUCKETS, curve.typical.size)
    }

    /**
     * Too little history is no curve, not a flat one.
     *
     * A median over two days would make every Monday after a quiet Friday look like a
     * breakout, which is the same reason the ratio holds its tongue.
     */
    @Test
    fun tooFewPriorSessionsProduceNothing() {
        assertNull(VolumeProfile.curve(sample(priorSessions = 2), today))
        assertNull(VolumeProfile.curve(emptyList(), today))
    }

    @Test
    fun priorSessionsAreCounted() {
        assertEquals(5, VolumeProfile.curve(sample(), today)!!.priorSessions)
        assertEquals(7, VolumeProfile.curve(sample(priorSessions = 7), today)!!.priorSessions)
    }

    @Test
    fun todayIsNotCountedAsItsOwnBaseline() {
        val curve = VolumeProfile.curve(sample(), today)!!
        // Today's mark 0 is 150; the median must be the prior sessions' 100, untouched.
        assertEquals(100.0, curve.typical[0]!!, 1e-9)
    }

    @Test
    fun marksOutsideTheSessionAreIgnored() {
        val stray = VolumeSample("BEL", today, VolumeProfile.BUCKETS + 5, 999.0)
        val curve = VolumeProfile.curve(sample() + stray, today)
        assertNotNull(curve)
        assertEquals(VolumeProfile.BUCKETS, curve!!.today.size)
    }

    @Test
    fun aSessionWithNoTodayDataStillHasABaseline() {
        val priorOnly = (1..5).flatMap { session(today - it, 100.0, 200.0) }
        val curve = VolumeProfile.curve(priorOnly, today)!!
        assertEquals(100.0, curve.typical[0]!!, 1e-9)
        assertNull(curve.today[0])
    }
}
