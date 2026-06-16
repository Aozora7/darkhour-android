package one.aozora.darkhour.ui.stats

import one.aozora.darkhour.core.model.SleepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class StatsScreenTest {
    @Test
    fun calculatesDailySleepTimeInBedEfficiencyAndCumulativeShift() {
        val metrics = calculateStatsMetrics(
            records = listOf(
                record(
                    start = "2026-06-01T22:00:00Z",
                    end = "2026-06-02T06:00:00Z",
                    durationHours = 8.0,
                    minutesAsleep = 420,
                ),
                record(
                    start = "2026-06-03T22:00:00Z",
                    end = "2026-06-04T06:00:00Z",
                    durationHours = 8.0,
                    minutesAsleep = 360,
                ),
            ),
            dailyDriftHours = 0.5,
        )

        assertEquals(3, metrics.daySpan)
        assertEquals(13.0 / 3.0, metrics.sleepHoursPerDay!!, 0.0001)
        assertEquals(16.0 / 3.0, metrics.timeInBedHoursPerDay!!, 0.0001)
        assertEquals(81, metrics.efficiencyPercent)
        assertEquals(0.0625, metrics.cumulativeShiftDays!!, 0.0001)
    }

    @Test
    fun returnsUnavailableMetricsForEmptyData() {
        val metrics = calculateStatsMetrics(emptyList(), dailyDriftHours = 0.5)

        assertEquals(0, metrics.daySpan)
        assertNull(metrics.sleepHoursPerDay)
        assertNull(metrics.timeInBedHoursPerDay)
        assertNull(metrics.efficiencyPercent)
        assertNull(metrics.cumulativeShiftDays)
    }

    private fun record(
        start: String,
        end: String,
        durationHours: Double,
        minutesAsleep: Int,
    ) = SleepRecord(
        logId = start.hashCode().toLong(),
        dateOfSleep = LocalDate.parse(start.substringBefore('T')),
        startTime = Instant.parse(start),
        endTime = Instant.parse(end),
        durationMs = (durationHours * 3_600_000).toLong(),
        durationHours = durationHours,
        efficiency = 0,
        minutesAsleep = minutesAsleep,
        minutesAwake = (durationHours * 60).toInt() - minutesAsleep,
        isMainSleep = true,
    )
}
