package one.aozora.darkhour.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.LocalAppSettings
import one.aozora.darkhour.ui.LocalHealthConnectState
import one.aozora.darkhour.ui.LocalSleepAnalysis
import java.time.Duration
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
) {
    val selectedAnalysis = LocalSleepAnalysis.current
    val (settings) = LocalAppSettings.current
    val healthConnect = LocalHealthConnectState.current
    var dataScope by rememberSaveable { mutableStateOf(StatsDataScope.SelectedPeriod) }
    val statsAllRecords = healthConnect.statsAllRecords
    val showDataScopeToggle = healthConnect.dataRange != HealthDataRange.ENTIRE_HISTORY
    LaunchedEffect(showDataScopeToggle) {
        if (!showDataScopeToggle) {
            dataScope = StatsDataScope.SelectedPeriod
        }
    }
    LaunchedEffect(
        dataScope,
        showDataScopeToggle,
        healthConnect.hasHistoryPermission,
        statsAllRecords,
        healthConnect.isStatsAllDataRefreshing,
    ) {
        if (
            showDataScopeToggle &&
            dataScope == StatsDataScope.AllAvailable &&
            healthConnect.hasHistoryPermission &&
            statsAllRecords == null &&
            !healthConnect.isStatsAllDataRefreshing
        ) {
            healthConnect.onRequestStatsAllData()
        }
    }

    val allDataRecords = remember(statsAllRecords, settings.includeNaps) {
        statsAllRecords
            ?.let { records -> if (settings.includeNaps) records else records.filter { it.isMainSleep } }
    }
    val allDataAnalysis = remember(allDataRecords, settings.forecastDays) {
        CircadianAnalyzer.analyze(allDataRecords.orEmpty(), extraDays = settings.forecastDays)
    }
    val allDataPeriodogram = remember(allDataRecords) {
        computePeriodogram(buildPeriodogramAnchors(allDataRecords.orEmpty()))
    }
    val records = when (dataScope) {
        StatsDataScope.SelectedPeriod -> selectedAnalysis.records
        StatsDataScope.AllAvailable -> allDataRecords.orEmpty()
    }
    val analysis = when (dataScope) {
        StatsDataScope.SelectedPeriod -> selectedAnalysis.analysis
        StatsDataScope.AllAvailable -> allDataAnalysis
    }
    val periodogram = when (dataScope) {
        StatsDataScope.SelectedPeriod -> selectedAnalysis.periodogram
        StatsDataScope.AllAvailable -> allDataPeriodogram
    }
    val showAllDataStats = dataScope == StatsDataScope.AllAvailable || !showDataScopeToggle
    val yearlyTauSeries = remember(analysis.days, showAllDataStats) {
        if (showAllDataStats) {
            calculateYearlyTauSeries(analysis.days)
        } else {
            emptyList()
        }
    }
    val mainSleeps = records.filter { it.isMainSleep }
    val metrics = calculateStatsMetrics(records, analysis.globalDailyDrift)
    val scopeSummary = statsScopeSummary(
        dataScope = dataScope,
        dataRange = healthConnect.dataRange,
        includeNaps = settings.includeNaps,
        recordCount = records.size,
        mainSleepsCount = mainSleeps.size,
    )
    val statusMessage = when {
        dataScope == StatsDataScope.AllAvailable && !healthConnect.hasHistoryPermission ->
            "Health history permission is needed for all available data."
        dataScope == StatsDataScope.AllAvailable && healthConnect.isStatsAllDataRefreshing ->
            "Loading all available data..."
        dataScope == StatsDataScope.AllAvailable && healthConnect.statsAllDataError != null ->
            healthConnect.statsAllDataError
        else -> null
    }
    fun selectDataScope(scope: StatsDataScope) {
        dataScope = scope
        if (scope == StatsDataScope.AllAvailable) {
            if (
                healthConnect.hasHistoryPermission &&
                statsAllRecords == null &&
                !healthConnect.isStatsAllDataRefreshing
            ) {
                healthConnect.onRequestStatsAllData()
            } else if (!healthConnect.hasHistoryPermission) {
                healthConnect.onRequestHistoryPermission()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                ) {
                    HeaderText(
                        scopeSummary = scopeSummary,
                        dataScope = dataScope,
                        showDataScopeToggle = showDataScopeToggle,
                        statusMessage = statusMessage,
                        onDataScopeChange = ::selectDataScope,
                    )
                    Spacer(Modifier.height(16.dp))
                    PeriodogramChart(
                        result = periodogram,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                    if (showAllDataStats) {
                        Spacer(Modifier.height(16.dp))
                        YearlyTauSection(
                            series = yearlyTauSeries,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        Metric("Tau", "%.2f h".format(analysis.globalTau), signedMinutes(analysis.globalDailyDrift * 60.0)),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric("Peak period", "%.2f h".format(periodogram.peakPeriod), "Power %.2f".format(periodogram.peakPower)),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric(
                            "Sleep per day",
                            metrics.sleepHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                            metrics.daySpan.takeIf { it > 0 }?.let { "Across $it days" } ?: "No data",
                        ),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric(
                            "Time in bed per day",
                            metrics.timeInBedHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                            metrics.efficiencyPercent?.let { "$it% efficiency" } ?: "No efficiency",
                        ),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        metric = Metric(
                            "Cumulative shift",
                            metrics.cumulativeShiftDays?.let(::signedDays) ?: "—",
                            metrics.daySpan.takeIf { it > 0 }?.let { "Over $it days" } ?: "No data",
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("stats_screen")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeaderText(
                    scopeSummary = scopeSummary,
                    dataScope = dataScope,
                    showDataScopeToggle = showDataScopeToggle,
                    statusMessage = statusMessage,
                    onDataScopeChange = ::selectDataScope,
                )

                PeriodogramChart(
                    result = periodogram,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 260.dp)
                        .aspectRatio(1.75f),
                )
                if (showAllDataStats) {
                    YearlyTauSection(
                        series = yearlyTauSeries,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                MetricRow(
                    left = Metric("Tau", "%.2f h".format(analysis.globalTau), signedMinutes(analysis.globalDailyDrift * 60.0)),
                    right = Metric("Peak period", "%.2f h".format(periodogram.peakPeriod), "Power %.2f".format(periodogram.peakPower)),
                )
                MetricRow(
                    left = Metric(
                        "Sleep per day",
                        metrics.sleepHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                        metrics.daySpan.takeIf { it > 0 }?.let { "Across $it days" } ?: "No data",
                    ),
                    right = Metric(
                        "Time in bed per day",
                        metrics.timeInBedHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                        metrics.efficiencyPercent?.let { "$it% efficiency" } ?: "No efficiency",
                    ),
                )
                MetricCard(
                    metric = Metric(
                        "Cumulative shift",
                        metrics.cumulativeShiftDays?.let(::signedDays) ?: "—",
                        metrics.daySpan.takeIf { it > 0 }?.let { "Over $it days" } ?: "No data",
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HeaderText(
    scopeSummary: String,
    dataScope: StatsDataScope,
    showDataScopeToggle: Boolean,
    statusMessage: String?,
    onDataScopeChange: (StatsDataScope) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Circadian stats", style = MaterialTheme.typography.headlineSmall)
        Text(
            scopeSummary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (showDataScopeToggle) {
            StatsScopeToggle(
                selectedScope = dataScope,
                onSelected = onDataScopeChange,
            )
        }
        if (statusMessage != null) {
            Text(
                statusMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatsScopeToggle(
    selectedScope: StatsDataScope,
    onSelected: (StatsDataScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsDataScope.entries.forEach { scope ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onSelected(scope) },
                label = { Text(scope.label) },
                modifier = Modifier.testTag(scope.testTag),
            )
        }
    }
}

private data class Metric(val label: String, val value: String, val detail: String)

@Composable
private fun YearlyTauSection(
    series: List<YearlyTauSeries>,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TauYearChart(
            series = series,
            modifier = modifier
                .height(260.dp)
                .testTag("tau_year_chart"),
        )
    }
}

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricCard(left, Modifier.weight(1f))
        MetricCard(right, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, style = MaterialTheme.typography.headlineSmall)
            Text(metric.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun signedMinutes(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f min/day".format(value)

private fun signedDays(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f days".format(value)

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
