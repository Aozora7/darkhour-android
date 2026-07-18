package one.aozora.darkhour.ui.stats

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HistoryPermissionState
import one.aozora.darkhour.ui.LocalAppSettings
import one.aozora.darkhour.ui.LocalHealthConnectState
import one.aozora.darkhour.ui.LocalSleepAnalysis
import one.aozora.darkhour.ui.LocalDeveloperCircadian
import java.time.YearMonth

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    onPagerScrollBlockedChange: (Boolean) -> Unit = {},
) {
    val selectedAnalysis = LocalSleepAnalysis.current
    val developerCircadian = LocalDeveloperCircadian.current
    val statsOverrides = remember(developerCircadian.activeOverrides) {
        statsCircadianOverrides(developerCircadian.activeOverrides)
    }
    val (settings, onSettingsChange) = LocalAppSettings.current
    val healthConnect = LocalHealthConnectState.current
    val statsAllRecords = healthConnect.statsAllRecords
    val showDataScopeToggle = healthConnect.dataRange != HealthDataRange.ENTIRE_HISTORY
    val providerAvailable = healthConnect.access.providerAvailable
    val dataScope = if (showDataScopeToggle && providerAvailable && settings.statsUseAllData) {
        StatsDataScope.AllAvailable
    } else {
        StatsDataScope.SelectedPeriod
    }
    LaunchedEffect(
        dataScope,
        showDataScopeToggle,
        providerAvailable,
        healthConnect.historyPermissionState,
        statsAllRecords,
        healthConnect.isStatsAllDataRefreshing,
    ) {
        if (
            showDataScopeToggle &&
            providerAvailable &&
            dataScope == StatsDataScope.AllAvailable &&
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
    val allDataAnalysis = remember(
        allDataRecords,
        settings.forecastDays,
        developerCircadian.algorithmId,
        statsOverrides,
    ) {
        CircadianAnalyzer.analyze(
            allDataRecords.orEmpty(),
            extraDays = settings.forecastDays,
            algorithmId = developerCircadian.algorithmId,
            overrides = statsOverrides,
        )
    }
    val selectedPeriodAnalysis = remember(
        selectedAnalysis.records,
        settings.forecastDays,
        developerCircadian.algorithmId,
        statsOverrides,
    ) {
        CircadianAnalyzer.analyze(
            selectedAnalysis.records,
            extraDays = settings.forecastDays,
            algorithmId = developerCircadian.algorithmId,
            overrides = statsOverrides,
        )
    }
    val records = when (dataScope) {
        StatsDataScope.SelectedPeriod -> selectedAnalysis.records
        StatsDataScope.AllAvailable -> allDataRecords.orEmpty()
    }
    val analysis = when (dataScope) {
        StatsDataScope.SelectedPeriod -> selectedPeriodAnalysis
        StatsDataScope.AllAvailable -> allDataAnalysis
    }
    val showAllDataStats = dataScope == StatsDataScope.AllAvailable || !showDataScopeToggle
    val currentMonth = YearMonth.now()
    val periodogramBounds = remember(records, currentMonth) {
        periodogramMonthBounds(records, currentMonth)
    }
    val resolvedPeriodogramRange = remember(settings.periodogramRange, periodogramBounds) {
        resolvePeriodogramMonthRange(settings.periodogramRange, periodogramBounds)
    }
    val rangedPeriodogramRecords = remember(records, resolvedPeriodogramRange) {
        filterPeriodogramRecords(records, resolvedPeriodogramRange)
    }
    val rangedPeriodogram = remember(rangedPeriodogramRecords) {
        computePeriodogram(buildPeriodogramAnchors(rangedPeriodogramRecords))
    }
    val periodogram = if (showAllDataStats) rangedPeriodogram else selectedAnalysis.periodogram
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
        dataScope == StatsDataScope.AllAvailable && healthConnect.isStatsAllDataRefreshing ->
            "Loading all available data..."
        dataScope == StatsDataScope.AllAvailable && healthConnect.statsAllDataError != null ->
            healthConnect.statsAllDataError
        dataScope == StatsDataScope.AllAvailable &&
            healthConnect.historyPermissionState != HistoryPermissionState.GRANTED ->
            "Includes all Dark Hour imports and other data Health Connect currently allows."
        else -> null
    }
    val isAllDataLoading =
        dataScope == StatsDataScope.AllAvailable &&
            statsAllRecords == null &&
            healthConnect.statsAllDataError == null
    fun selectDataScope(scope: StatsDataScope) {
        if (scope == StatsDataScope.AllAvailable && !providerAvailable) return
        onSettingsChange(settings.copy(statsUseAllData = scope == StatsDataScope.AllAvailable))
        if (scope == StatsDataScope.AllAvailable) {
            if (statsAllRecords == null && !healthConnect.isStatsAllDataRefreshing) {
                healthConnect.onRequestStatsAllData()
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
                        allAvailableEnabled = providerAvailable,
                        statusMessage = statusMessage,
                        onDataScopeChange = ::selectDataScope,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (showAllDataStats && records.isNotEmpty()) {
                        PeriodogramRangeControl(
                            selection = settings.periodogramRange,
                            bounds = periodogramBounds,
                            useIsoDateTime = settings.useIsoDateTime,
                            onSelectionChange = {
                                onSettingsChange(settings.copy(periodogramRange = it))
                            },
                            onDraggingChange = onPagerScrollBlockedChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (isAllDataLoading) {
                        StatsLoadingIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    } else {
                        PeriodogramChart(
                            result = periodogram,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                    if (showAllDataStats && !isAllDataLoading) {
                        Spacer(Modifier.height(16.dp))
                        YearlyTauSection(
                            series = yearlyTauSeries,
                            selectedYears = selectedTauYears(yearlyTauSeries, settings.selectedTauYears),
                            onSelectedYearsChange = {
                                onSettingsChange(settings.copy(selectedTauYears = it))
                            },
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
                    allAvailableEnabled = providerAvailable,
                    statusMessage = statusMessage,
                    onDataScopeChange = ::selectDataScope,
                )

                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (showAllDataStats && records.isNotEmpty()) {
                        PeriodogramRangeControl(
                            selection = settings.periodogramRange,
                            bounds = periodogramBounds,
                            useIsoDateTime = settings.useIsoDateTime,
                            onSelectionChange = {
                                onSettingsChange(settings.copy(periodogramRange = it))
                            },
                            onDraggingChange = onPagerScrollBlockedChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (isAllDataLoading) {
                        StatsLoadingIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    } else {
                        PeriodogramChart(
                            result = periodogram,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 260.dp)
                                .aspectRatio(1.75f),
                        )
                    }
                }
                if (showAllDataStats && !isAllDataLoading) {
                    YearlyTauSection(
                        series = yearlyTauSeries,
                        selectedYears = selectedTauYears(yearlyTauSeries, settings.selectedTauYears),
                        onSelectedYearsChange = {
                            onSettingsChange(settings.copy(selectedTauYears = it))
                        },
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
