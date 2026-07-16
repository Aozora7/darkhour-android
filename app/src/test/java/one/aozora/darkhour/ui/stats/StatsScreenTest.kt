package one.aozora.darkhour.ui.stats

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.PeriodogramPoint
import one.aozora.darkhour.data.HealthDataRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun statsScopeSummaryShowsRangeCountsAndNapFilter() {
        assertEquals(
            "Health Connect · Last 90 days · 123 records · 92 main sleeps · naps included",
            statsScopeSummary(
                dataRange = HealthDataRange.custom(90),
                includeNaps = true,
                recordCount = 123,
                mainSleepsCount = 92,
            ),
        )
        assertEquals(
            "Health Connect · All history · 1 record · 1 main sleep · naps excluded",
            statsScopeSummary(
                dataRange = HealthDataRange.ENTIRE_HISTORY,
                includeNaps = false,
                recordCount = 1,
                mainSleepsCount = 1,
            ),
        )
        assertEquals(
            "Health Connect · All available data · 200 records · 150 main sleeps · naps excluded",
            statsScopeSummary(
                dataScope = StatsDataScope.AllAvailable,
                dataRange = HealthDataRange.custom(90),
                includeNaps = false,
                recordCount = 200,
                mainSleepsCount = 150,
            ),
        )
    }

    @Test
    fun calculatesYearlyTauSeriesByDayOfYear() {
        val series = calculateYearlyTauSeries(
            days = listOf(
                circadianDay("2026-06-01", tau = 24.8, weight = 1.0, hasAnchor = true),
                circadianDay("2025-12-31", tau = 24.4, weight = 0.8, hasAnchor = true),
                circadianDay("2025-01-02", tau = 24.2, weight = 1.0, hasAnchor = true),
                circadianDay("2026-06-02", tau = 24.9, weight = 0.5, hasAnchor = false),
                circadianDay("2026-06-03", tau = 27.0, weight = 1.0, hasAnchor = true, isGap = true),
                circadianDay("2026-06-04", tau = 27.0, weight = 1.0, hasAnchor = true, isForecast = true),
                circadianDay("2026-06-05", tau = 27.0, weight = 0.0, hasAnchor = true),
            ),
        )

        assertEquals(listOf(2025, 2026), series.map { it.year })
        assertEquals(listOf(2, 365), series[0].points.map { it.dayOfYear })
        assertEquals(listOf(24.2, 24.4), series[0].points.map { it.tauHours })
        assertEquals(listOf(152, 153), series[1].points.map { it.dayOfYear })
        assertEquals(listOf(24.8, 24.9), series[1].points.map { it.tauHours })
        assertEquals(listOf(1.0, 0.5), series[1].points.map { it.confidence })
    }

    @Test
    fun tauAxisRangeUsesDataBoundsWithoutForcingTwentyFourHours() {
        val range = tauAxisRange(listOf(24.82, 24.91, 25.03))

        assertEquals(24.7, range.min, 0.0001)
        assertEquals(25.2, range.max, 0.0001)
    }

    @Test
    fun astronomicalSeasonBandsCoverApproximateYearBoundaries() {
        val bands = astronomicalSeasonBands()

        assertEquals(1, bands.first().startDay)
        assertEquals(366, bands.last().endDay)
        assertEquals(listOf(79, 172, 265, 355), bands.dropLast(1).map { it.endDay })
    }

    @Test
    fun yearGradientColorsSeparateDistantYears() {
        val first = yearGradientColor(year = 2020, minYear = 2020, maxYear = 2024)
        val middle = yearGradientColor(year = 2022, minYear = 2020, maxYear = 2024)
        val last = yearGradientColor(year = 2024, minYear = 2020, maxYear = 2024)

        assertNotEquals(first, middle)
        assertNotEquals(first, last)
        assertNotEquals(middle, last)
    }

    @Test
    fun defaultsToNewestFourTauYearsAndHonorsPersistedSelection() {
        val series = (2020..2025).map { YearlyTauSeries(it, emptyList()) }

        assertEquals(setOf(2022, 2023, 2024, 2025), selectedTauYears(series, null))
        assertEquals(setOf(2020, 2024), selectedTauYears(series, setOf(2019, 2020, 2024)))
        assertEquals(emptySet<Int>(), selectedTauYears(series, emptySet()))
    }

    @Test
    fun periodogramSelectionUsesNearestPointWithinPlotBounds() {
        val points = listOf(
            PeriodogramPoint(period = 23.0, power = 0.1),
            PeriodogramPoint(period = 24.0, power = 0.4),
            PeriodogramPoint(period = 25.0, power = 0.2),
        )

        assertEquals(points[1], periodogramPointAtX(points, 51f, 0f, 100f))
        assertNull(periodogramPointAtX(points, -1f, 0f, 100f))
        assertNull(periodogramPointAtX(points, 50f, 100f, 100f))
    }

    @Test
    fun periodogramGestureSpeedSeparatesSwipeFromSlowScrub() {
        assertTrue(
            horizontalSpeedDpPerSecond(
                displacementPx = 300f,
                elapsedMillis = 500L,
                density = 1f,
            ) > QUICK_SWIPE_SPEED_DP_PER_SECOND,
        )
        assertTrue(
            horizontalSpeedDpPerSecond(
                displacementPx = 300f,
                elapsedMillis = 3_000L,
                density = 1f,
            ) < QUICK_SWIPE_SPEED_DP_PER_SECOND,
        )
    }

    @Test
    fun tauTooltipUsesNearestValueForEachEnabledYearNewestFirst() {
        val values = tauTooltipValues(
            series = listOf(
                YearlyTauSeries(
                    year = 2025,
                    points = listOf(
                        YearlyTauPoint(100, 24.2, 1.0),
                        YearlyTauPoint(110, 24.4, 1.0),
                    ),
                ),
                YearlyTauSeries(
                    year = 2026,
                    points = listOf(
                        YearlyTauPoint(100, 24.6, 1.0),
                        YearlyTauPoint(110, 24.8, 1.0),
                    ),
                ),
            ),
            dayOfYear = 105,
        )

        assertEquals(listOf(2026, 2025), values.map { it.year })
        assertEquals(listOf(105, 105), values.map { it.point.dayOfYear })
        assertEquals(24.7, values[0].point.tauHours, 0.0001)
        assertEquals(24.3, values[1].point.tauHours, 0.0001)
        assertEquals("Feb 29", formatTauDayOfYear(60))
    }

    @Test
    fun tauTooltipDoesNotExtrapolatePastAYearsPlottedRange() {
        val values = tauTooltipValues(
            series = listOf(
                YearlyTauSeries(
                    year = 2026,
                    points = listOf(
                        YearlyTauPoint(100, 24.6, 1.0),
                        YearlyTauPoint(110, 24.8, 1.0),
                    ),
                ),
            ),
            dayOfYear = 120,
        )

        assertEquals(emptyList<TauTooltipValue>(), values)
    }

    @Test
    fun tauDaySelectionMapsPlotEdgesToCalendarYear() {
        assertEquals(1, tauDayAtX(10f, 10f, 110f))
        assertEquals(366, tauDayAtX(110f, 10f, 110f))
        assertNull(tauDayAtX(9f, 10f, 110f))
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

    private fun circadianDay(
        date: String,
        tau: Double,
        weight: Double,
        hasAnchor: Boolean,
        isGap: Boolean = false,
        isForecast: Boolean = false,
    ): CircadianDay {
        val localDate = LocalDate.parse(date)
        return CircadianDay(
            date = localDate,
            nightStartHour = 22.0,
            nightEndHour = 6.0,
            confidenceScore = weight,
            confidence = CircadianConfidence.HIGH,
            localTau = tau,
            localDrift = tau - 24.0,
            anchorSleep = if (hasAnchor) {
                record(
                    start = "${date}T22:00:00Z",
                    end = localDate.plusDays(1).toString() + "T06:00:00Z",
                    durationHours = 8.0,
                    minutesAsleep = 420,
                )
            } else {
                null
            },
            isForecast = isForecast,
            isGap = isGap,
        )
    }
}
