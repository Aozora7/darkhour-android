package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramLayout
import one.aozora.darkhour.ui.actogram.ActogramLayoutEngine
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import one.aozora.darkhour.ui.settings.AppSettings
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class DarkHourAppState(
    val sleepAnalysis: SleepAnalysisState,
    val appSettings: AppSettingsState,
    val actogramDisplay: ActogramDisplayState,
    val developerCircadian: DeveloperCircadianState,
    val schedule: ScheduleState,
    val healthConnect: HealthConnectState,
)

@Composable
fun rememberDarkHourAppState(
    records: List<SleepRecord>,
    initialSettings: AppSettings,
    onAppSettingsChange: (AppSettings) -> Unit,
    initialDisplayOptions: ActogramDisplayOptions,
    onDisplayOptionsChange: (ActogramDisplayOptions) -> Unit,
    initialScheduleEntries: List<ScheduleEntry>,
    onScheduleEntriesChange: (List<ScheduleEntry>) -> Unit,
    healthConnectAccess: HealthConnectAccess,
    healthDataRange: HealthDataRange,
    hasHistoryPermission: Boolean,
    statsAllRecords: List<SleepRecord>?,
    isRefreshing: Boolean,
    isStatsAllDataRefreshing: Boolean,
    importedRecordCount: Int,
    expectedRecordCount: Int?,
    isImportPartial: Boolean,
    importPhase: HealthImportPhase,
    importError: String?,
    statsAllDataError: String?,
    totalHistoryDays: Int?,
    onRequestHealthPermissions: () -> Unit,
    onRequestHistoryPermission: () -> Unit,
    onRequestStatsAllData: () -> Unit,
    onHealthDataRangeChange: (HealthDataRange) -> Unit,
): DarkHourAppState {
    var options by remember { mutableStateOf(initialDisplayOptions) }
    var settings by remember { mutableStateOf(initialSettings) }
    var scheduleEntries by remember { mutableStateOf(initialScheduleEntries) }
    var pendingScheduleEditId by remember { mutableStateOf<Long?>(null) }
    var developerAlgorithmId by remember { mutableStateOf(CircadianAlgorithmRegistry.defaultAlgorithm.id) }
    var developerOverrides by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }

    val developerCircadian = DeveloperCircadianState(
        algorithmId = developerAlgorithmId,
        overridesByAlgorithm = developerOverrides,
        onAlgorithmChange = { algorithmId ->
            developerAlgorithmId = CircadianAlgorithmRegistry.algorithm(algorithmId).id
        },
        onParameterChange = { key, value ->
            developerOverrides = developerOverrides + (
                developerAlgorithmId to (developerOverrides[developerAlgorithmId].orEmpty() + (key to value))
            )
        },
        onParameterReset = { key ->
            val active = developerOverrides[developerAlgorithmId].orEmpty() - key
            developerOverrides = if (active.isEmpty()) {
                developerOverrides - developerAlgorithmId
            } else {
                developerOverrides + (developerAlgorithmId to active)
            }
        },
    )

    val filteredRecords = remember(records, settings.includeNaps) {
        if (settings.includeNaps) records else records.filter { it.isMainSleep }
    }
    val analysis = remember(filteredRecords, settings.forecastDays, developerAlgorithmId, developerCircadian.activeOverrides) {
        CircadianAnalyzer.analyze(
            filteredRecords,
            extraDays = settings.forecastDays,
            algorithmId = developerAlgorithmId,
            overrides = developerCircadian.activeOverrides,
        )
    }
    val actogramForecastDays = settings.forecastDays.let { days ->
        if (days > 0) days + 1 else 0
    }
    val actogramAnalysis = remember(filteredRecords, actogramForecastDays, developerAlgorithmId, developerCircadian.activeOverrides) {
        CircadianAnalyzer.analyze(
            filteredRecords,
            extraDays = actogramForecastDays,
            algorithmId = developerAlgorithmId,
            overrides = developerCircadian.activeOverrides,
        )
    }
    val hideActogramForecastTail = actogramAnalysis.days.count { it.isForecast } >
        analysis.days.count { it.isForecast }
    val periodogram = remember(filteredRecords) {
        computePeriodogram(buildPeriodogramAnchors(filteredRecords))
    }
    val rowHours = when (options.timeScale) {
        ActogramTimeScale.HOURS_24 -> 24.0
        ActogramTimeScale.CIRCADIAN_TAU -> analysis.globalTau
        ActogramTimeScale.CUSTOM -> options.customHours.toDouble()
    }
    val baseLayout = remember(filteredRecords, actogramAnalysis.days, hideActogramForecastTail, rowHours) {
        ActogramLayoutEngine.build(
            records = filteredRecords,
            circadianDays = actogramAnalysis.days,
            rowHours = rowHours,
        ).withHiddenActogramForecastTail(hideActogramForecastTail)
    }
    val layout = remember(baseLayout, filteredRecords, actogramAnalysis.days, scheduleEntries, hideActogramForecastTail, rowHours) {
        if (scheduleEntries.isEmpty()) {
            baseLayout
        } else if (scheduleEntries.any { it.date != null }) {
            ActogramLayoutEngine.build(
                records = filteredRecords,
                circadianDays = actogramAnalysis.days,
                scheduleEntries = scheduleEntries,
                rowHours = rowHours,
            ).withHiddenActogramForecastTail(hideActogramForecastTail)
        } else {
            ActogramLayoutEngine.withScheduleEntries(baseLayout, scheduleEntries)
        }
    }

    LaunchedEffect(actogramAnalysis.days, layout) {
        logCircadianDebugDiagnostics(actogramAnalysis.days, layout)
    }
    LaunchedEffect(options) {
        delay(300.milliseconds)
        onDisplayOptionsChange(options)
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        onAppSettingsChange(updated)
    }

    fun updateDisplayOptions(updated: ActogramDisplayOptions) {
        options = updated
    }

    fun updateScheduleEntries(updated: List<ScheduleEntry>) {
        scheduleEntries = updated
        onScheduleEntriesChange(updated)
    }

    return DarkHourAppState(
        sleepAnalysis = SleepAnalysisState(
            records = filteredRecords,
            analysis = analysis,
            periodogram = periodogram,
        ),
        appSettings = AppSettingsState(
            settings = settings,
            onSettingsChange = ::updateSettings,
        ),
        actogramDisplay = ActogramDisplayState(
            layout = layout,
            options = options,
            onOptionsChange = ::updateDisplayOptions,
        ),
        developerCircadian = developerCircadian,
        schedule = ScheduleState(
            entries = scheduleEntries,
            pendingEditId = pendingScheduleEditId,
            onEntriesChange = ::updateScheduleEntries,
            onEditConsumed = { pendingScheduleEditId = null },
            onEditEntry = { entryId -> pendingScheduleEditId = entryId },
        ),
        healthConnect = HealthConnectState(
            access = healthConnectAccess,
            dataRange = healthDataRange,
            hasHistoryPermission = hasHistoryPermission,
            statsAllRecords = statsAllRecords,
            isRefreshing = isRefreshing,
            isStatsAllDataRefreshing = isStatsAllDataRefreshing,
            importedRecordCount = importedRecordCount,
            expectedRecordCount = expectedRecordCount,
            isImportPartial = isImportPartial,
            importPhase = importPhase,
            importError = importError,
            statsAllDataError = statsAllDataError,
            totalHistoryDays = totalHistoryDays,
            onRequestHealthPermissions = onRequestHealthPermissions,
            onRequestHistoryPermission = onRequestHistoryPermission,
            onRequestStatsAllData = onRequestStatsAllData,
            onDataRangeChange = onHealthDataRangeChange,
        ),
    )
}

private fun ActogramLayout.withHiddenActogramForecastTail(
    hideForecastTail: Boolean,
): ActogramLayout =
    if (hideForecastTail && rows.isNotEmpty()) {
        copy(hiddenChronologicalTailRows = 1)
    } else {
        this
    }
