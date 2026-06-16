package one.aozora.darkhour.core.circadian.csf

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorsTest {
    @Test
    fun computeAnchorWeightReturnsHigherWeightForHigherQuality() {
        val high = computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.9))
        val low = computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.5))
        assertNotNull(high)
        assertNotNull(low)
        assertTrue(high!! > low!!)
    }

    @Test
    fun computeAnchorWeightReturnsHigherWeightForLongerDuration() {
        val short = computeAnchorWeight(makeSleepRecord(durationHours = 5.0, sleepScore = 0.85))
        val long = computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.85))
        assertTrue(long!! > short!!)
    }

    @Test
    fun computeAnchorWeightCapsDurationFactorAtSevenHours() {
        val at7 = computeAnchorWeight(makeSleepRecord(durationHours = 7.0, sleepScore = 0.85))
        val at10 = computeAnchorWeight(makeSleepRecord(durationHours = 10.0, sleepScore = 0.85))
        assertClose(at7!!, at10!!)
    }

    @Test
    fun computeAnchorWeightAppliesNapMultiplier() {
        val main = computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.85, isMainSleep = true))
        val nap = computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.85, isMainSleep = false))
        assertClose(nap!!, main!! * 0.15)
    }

    @Test
    fun computeAnchorWeightReturnsNullForLowQualityOrShortDuration() {
        assertNull(computeAnchorWeight(makeSleepRecord(durationHours = 3.5, sleepScore = 0.85)))
        assertNull(computeAnchorWeight(makeSleepRecord(durationHours = 8.0, sleepScore = 0.02)))
        assertNull(computeAnchorWeight(makeSleepRecord(durationHours = 4.5, sleepScore = 0.1)))
    }

    @Test
    fun computeAnchorWeightScalesLinearlyBetweenFourAndSevenHours() {
        assertNull(computeAnchorWeight(makeSleepRecord(durationHours = 4.0, sleepScore = 1.0)))
        assertClose(computeAnchorWeight(makeSleepRecord(durationHours = 5.0, sleepScore = 1.0))!!, 1.0 / 3.0, 0.0001)
        assertClose(computeAnchorWeight(makeSleepRecord(durationHours = 6.0, sleepScore = 1.0))!!, 2.0 / 3.0, 0.0001)
        assertClose(computeAnchorWeight(makeSleepRecord(durationHours = 7.0, sleepScore = 1.0))!!, 1.0)
    }

    @Test
    fun sleepMidpointHourComputesRelativeHour() {
        val firstDateMs = LocalDate.parse("2024-01-01").utcStartInstant().toEpochMilli()
        val record = makeSleepRecord(
            startTime = Instant.parse("2024-01-01T22:00:00Z"),
            endTime = Instant.parse("2024-01-02T06:00:00Z"),
            durationMs = 8L * 3_600_000L,
        )

        assertClose(sleepMidpointHour(record, firstDateMs), 26.0, 0.1)
    }

    @Test
    fun sleepMidpointHourHandlesMultiDayOffsetAndZeroDuration() {
        val firstDateMs = LocalDate.parse("2024-01-01").utcStartInstant().toEpochMilli()
        val multiDay = makeSleepRecord(
            startTime = Instant.parse("2024-01-03T23:00:00Z"),
            endTime = Instant.parse("2024-01-04T07:00:00Z"),
            durationMs = 8L * 3_600_000L,
        )
        val zero = makeSleepRecord(
            startTime = Instant.parse("2024-01-01T12:00:00Z"),
            endTime = Instant.parse("2024-01-01T12:00:00Z"),
            durationMs = 0,
        )

        assertClose(sleepMidpointHour(multiDay, firstDateMs), 75.0, 0.1)
        assertClose(sleepMidpointHour(zero, firstDateMs), 12.0, 0.1)
    }

    @Test
    fun prepareAnchorsFiltersSortsAndSelectsBestPerDate() {
        val firstDate = LocalDate.parse("2024-01-01")
        val firstDateMs = firstDate.utcStartInstant().toEpochMilli()
        val records = listOf(
            makeSleepRecord(logId = 1, dateOfSleep = LocalDate.parse("2024-01-03"), startTime = Instant.parse("2024-01-03T23:00:00Z")),
            makeSleepRecord(logId = 2, dateOfSleep = LocalDate.parse("2024-01-01"), startTime = Instant.parse("2024-01-01T23:00:00Z"), durationHours = 6.0, sleepScore = 0.7),
            makeSleepRecord(logId = 3, dateOfSleep = LocalDate.parse("2024-01-01"), startTime = Instant.parse("2024-01-01T22:00:00Z"), durationHours = 8.0, sleepScore = 0.9),
            makeSleepRecord(logId = 4, dateOfSleep = LocalDate.parse("2024-01-02"), startTime = Instant.parse("2024-01-02T23:00:00Z"), durationHours = 3.0),
        )

        val anchors = prepareAnchors(records, firstDate, firstDateMs)

        assertEquals(2, anchors.size)
        assertEquals(0, anchors[0].dayNumber)
        assertEquals(2, anchors[1].dayNumber)
        assertEquals(3, anchors[0].record.logId)
        assertClose(anchors[0].weight, 0.9, 0.0001)
    }

    @Test
    fun prepareAnchorsPrefersMainSleepOverNapWhenWeightsDiffer() {
        val firstDate = LocalDate.parse("2024-01-01")
        val firstDateMs = firstDate.utcStartInstant().toEpochMilli()
        val records = listOf(
            makeSleepRecord(logId = 1, dateOfSleep = LocalDate.parse("2024-01-01"), isMainSleep = true, sleepScore = 0.85),
            makeSleepRecord(logId = 2, dateOfSleep = LocalDate.parse("2024-01-01"), isMainSleep = false, sleepScore = 0.85),
        )

        val anchors = prepareAnchors(records, firstDate, firstDateMs)

        assertEquals(1, anchors.size)
        assertTrue(anchors[0].record.isMainSleep)
    }
}
