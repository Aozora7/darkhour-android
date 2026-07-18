package one.aozora.darkhour.ui.stats

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.settings.PeriodogramRangeSelection
import java.time.Duration
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

internal const val STATS_CSF_SMOOTHING_DAYS = 14.0

internal data class PeriodogramMonthBounds(
    val newest: YearMonth,
    val oldest: YearMonth,
) {
    val lastMonthOffset: Int
        get() = ChronoUnit.MONTHS.between(oldest, newest).toInt().coerceAtLeast(0)
}

internal data class ResolvedPeriodogramMonthRange(
    val newest: YearMonth,
    val oldest: YearMonth,
)

internal fun periodogramMonthBounds(
    records: List<SleepRecord>,
    currentMonth: YearMonth,
): PeriodogramMonthBounds {
    val oldestRecordMonth = records
        .minOfOrNull { YearMonth.from(it.dateOfSleep) }
        ?.coerceAtMost(currentMonth)
        ?: currentMonth
    return PeriodogramMonthBounds(newest = currentMonth, oldest = oldestRecordMonth)
}

internal fun resolvePeriodogramMonthRange(
    selection: PeriodogramRangeSelection,
    bounds: PeriodogramMonthBounds,
): ResolvedPeriodogramMonthRange {
    val newest = (selection.newestMonth ?: bounds.newest).coerceIn(bounds.oldest, bounds.newest)
    val oldest = (selection.oldestMonth ?: bounds.oldest).coerceIn(bounds.oldest, bounds.newest)
    return if (newest >= oldest) {
        ResolvedPeriodogramMonthRange(newest = newest, oldest = oldest)
    } else {
        ResolvedPeriodogramMonthRange(newest = oldest, oldest = newest)
    }
}

internal fun periodogramRangeOffsets(
    selection: PeriodogramRangeSelection,
    bounds: PeriodogramMonthBounds,
): ClosedFloatingPointRange<Float> {
    val resolved = resolvePeriodogramMonthRange(selection, bounds)
    return monthOffset(bounds.newest, resolved.newest).toFloat()..
        monthOffset(bounds.newest, resolved.oldest).toFloat()
}

internal fun periodogramSelectionForOffsets(
    offsets: ClosedFloatingPointRange<Float>,
    bounds: PeriodogramMonthBounds,
): PeriodogramRangeSelection {
    val newestOffset = offsets.start.roundToInt().coerceIn(0, bounds.lastMonthOffset)
    val oldestOffset = offsets.endInclusive.roundToInt().coerceIn(newestOffset, bounds.lastMonthOffset)
    return PeriodogramRangeSelection(
        newestMonth = if (newestOffset == 0) null else bounds.newest.minusMonths(newestOffset.toLong()),
        oldestMonth = if (oldestOffset == bounds.lastMonthOffset) {
            null
        } else {
            bounds.newest.minusMonths(oldestOffset.toLong())
        },
    )
}

internal fun periodogramYearBoundaryOffsets(bounds: PeriodogramMonthBounds): List<Int> =
    (0..bounds.lastMonthOffset).filter { monthOffset ->
        bounds.newest.minusMonths(monthOffset.toLong()).monthValue == 1
    }

internal fun filterPeriodogramRecords(
    records: List<SleepRecord>,
    range: ResolvedPeriodogramMonthRange,
): List<SleepRecord> = records.filter { record ->
    YearMonth.from(record.dateOfSleep) in range.oldest..range.newest
}

private fun monthOffset(newest: YearMonth, month: YearMonth): Int =
    ChronoUnit.MONTHS.between(month, newest).toInt().coerceAtLeast(0)

internal fun statsCircadianOverrides(overrides: Map<String, Double>): Map<String, Double> =
    overrides + ("smoothing_days" to STATS_CSF_SMOOTHING_DAYS)

internal fun statsScopeSummary(
    dataScope: StatsDataScope = StatsDataScope.SelectedPeriod,
    dataRange: HealthDataRange,
    includeNaps: Boolean,
    recordCount: Int,
    mainSleepsCount: Int,
): String {
    val rangeLabel = when (dataRange) {
        HealthDataRange.DefaultPeriod -> "Last 30 days"
        HealthDataRange.EntireHistory -> "All available"
        is HealthDataRange.Custom -> "Last ${dataRange.days} days"
    }
    val scopeLabel = when (dataScope) {
        StatsDataScope.SelectedPeriod -> rangeLabel
        StatsDataScope.AllAvailable -> "All available data"
    }
    val napLabel = if (includeNaps) "naps included" else "naps excluded"
    val recordsLabel = "$recordCount ${"record".pluralized(recordCount)}"
    val mainSleepsLabel = "$mainSleepsCount main ${"sleep".pluralized(mainSleepsCount)}"
    return "Health Connect · $scopeLabel · $recordsLabel · $mainSleepsLabel · $napLabel"
}

internal enum class StatsDataScope(
    val label: String,
    val testTag: String,
) {
    SelectedPeriod("Selected period", "stats_scope_selected"),
    AllAvailable("All data", "stats_scope_all"),
}

internal data class YearlyTauPoint(
    val dayOfYear: Int,
    val tauHours: Double,
    val confidence: Double,
)

internal data class YearlyTauSeries(
    val year: Int,
    val points: List<YearlyTauPoint>,
)

internal fun selectedTauYears(
    series: List<YearlyTauSeries>,
    persistedSelection: Set<Int>?,
): Set<Int> {
    val availableYears = series.map { it.year }.toSet()
    return persistedSelection
        ?.intersect(availableYears)
        ?: availableYears.sortedDescending().take(DEFAULT_SELECTED_TAU_YEAR_COUNT).toSet()
}

internal fun calculateYearlyTauSeries(
    days: List<CircadianDay>,
): List<YearlyTauSeries> =
    days
        .filter {
            !it.isForecast &&
                !it.isGap &&
                it.confidenceScore > 0.0
        }
        .groupBy { it.date.year }
        .toSortedMap()
        .map { (year, yearDays) ->
            YearlyTauSeries(
                year = year,
                points = yearDays
                    .sortedBy { it.date }
                    .map {
                        YearlyTauPoint(
                            dayOfYear = it.date.dayOfYear,
                            tauHours = it.localTau,
                            confidence = it.confidenceScore,
                        )
                    },
            )
        }

private fun String.pluralized(count: Int): String =
    if (count == 1) this else "${this}s"

internal data class StatsMetrics(
    val daySpan: Int,
    val sleepHoursPerDay: Double?,
    val timeInBedHoursPerDay: Double?,
    val efficiencyPercent: Int?,
    val cumulativeShiftDays: Double?,
)

internal fun calculateStatsMetrics(
    records: List<SleepRecord>,
    dailyDriftHours: Double,
): StatsMetrics {
    if (records.isEmpty()) {
        return StatsMetrics(
            daySpan = 0,
            sleepHoursPerDay = null,
            timeInBedHoursPerDay = null,
            efficiencyPercent = null,
            cumulativeShiftDays = null,
        )
    }

    val firstStart = records.minOf { it.startTime }
    val lastEnd = records.maxOf { it.endTime }
    val daySpan = (Duration.between(firstStart, lastEnd).toMillis() / MILLIS_PER_DAY)
        .roundToInt() + 1
    val totalTimeInBedHours = records.sumOf { it.durationHours }
    val totalMinutesAsleep = records.sumOf { it.minutesAsleep }
    val totalTimeInBedMinutes = totalTimeInBedHours * 60.0

    return StatsMetrics(
        daySpan = daySpan,
        sleepHoursPerDay = totalMinutesAsleep / 60.0 / daySpan,
        timeInBedHoursPerDay = totalTimeInBedHours / daySpan,
        efficiencyPercent = totalTimeInBedMinutes
            .takeIf { it > 0.0 }
            ?.let { (totalMinutesAsleep / it * 100.0).roundToInt() },
        cumulativeShiftDays = dailyDriftHours * daySpan / 24.0,
    )
}

private const val MILLIS_PER_DAY = 86_400_000.0
private const val DEFAULT_SELECTED_TAU_YEAR_COUNT = 4
