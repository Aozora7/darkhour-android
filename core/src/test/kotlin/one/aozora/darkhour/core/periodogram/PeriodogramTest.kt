package one.aozora.darkhour.core.periodogram

import one.aozora.darkhour.core.circadian.csf.makeSleepRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodogramTest {
    private fun syntheticAnchors(tau: Double, days: Int, noise: Double = 0.0) =
        (0 until days).map { day ->
            PeriodogramAnchor(
                dayNumber = day,
                midpointHour = 3.0 + day * (tau - 24.0) + noise * sin(day * 0.1),
                weight = 1.0,
            )
        }

    @Test
    fun findsKnownPeriods() {
        assertTrue(abs(computePeriodogram(syntheticAnchors(24.0, 200)).peakPeriod - 24.0) < 0.2)
        assertTrue(abs(computePeriodogram(syntheticAnchors(24.5, 200)).peakPeriod - 24.5) < 0.2)
        assertTrue(abs(computePeriodogram(syntheticAnchors(25.0, 200)).peakPeriod - 25.0) < 0.4)
    }

    @Test
    fun reportsThresholdAndIncludes24HourReference() {
        val result = computePeriodogram(syntheticAnchors(24.5, 100))

        assertTrue(result.significanceThreshold > 0.0)
        assertTrue(result.power24h > 0.0)
        assertTrue(result.trimmedPoints.minOf { it.period } <= 24.0)
        assertTrue(result.trimmedPoints.maxOf { it.period } >= 24.0)
    }

    @Test
    fun returnsEmptyForTooFewAnchors() {
        val result = computePeriodogram(
            listOf(
                PeriodogramAnchor(0, 3.0, 1.0),
                PeriodogramAnchor(1, 3.5, 1.0),
            ),
        )

        assertEquals(PeriodogramResult.Empty, result)
    }

    @Test
    fun buildsAnchorsUsingLocalOffsetAndFiltersRecords() {
        val date = LocalDate.parse("2024-01-01")
        val offset = ZoneOffset.ofHours(2)
        val records = listOf(
            makeSleepRecord(
                logId = 1,
                dateOfSleep = date,
                startTime = Instant.parse("2024-01-01T20:00:00Z"),
                endTime = Instant.parse("2024-01-02T04:00:00Z"),
                sleepScore = 1.0,
            ).copy(startZoneOffset = offset),
            makeSleepRecord(logId = 2, dateOfSleep = date.plusDays(1), isMainSleep = false),
            makeSleepRecord(logId = 3, dateOfSleep = date.plusDays(2), durationHours = 3.5),
        )

        val anchors = buildPeriodogramAnchors(records)

        assertEquals(1, anchors.size)
        assertEquals(0, anchors.first().dayNumber)
        assertTrue(abs(anchors.first().midpointHour - 26.0) < 0.01)
        assertTrue(abs(anchors.first().weight - 1.0) < 0.01)
    }

    @Test
    fun respectsCustomRange() {
        val result = computePeriodogram(
            syntheticAnchors(24.5, 100),
            PeriodogramOptions(minPeriod = 24.0, maxPeriod = 25.0),
        )

        assertTrue(result.points.all { it.period in 24.0..25.0 })
    }
}
