package one.aozora.darkhour.ui.stats

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.data.HealthDataRange
import java.time.Duration
import kotlin.math.roundToInt

internal const val STATS_CSF_SMOOTHING_DAYS = 14.0

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
        HealthDataRange.EntireHistory -> "All history"
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
