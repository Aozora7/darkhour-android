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
import one.aozora.darkhour.BuildConfig
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.data.HealthConnectFileOperation
import one.aozora.darkhour.data.SleepFileImportResult
import one.aozora.darkhour.data.SleepExportPreparation
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.SleepExportResult
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
    analysisRecords: List<SleepRecord>,
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
    fileWriteSupported: Boolean,
    fileImportedRecordCount: Int,
    fileOperation: HealthConnectFileOperation,
    fileImportResult: SleepFileImportResult?,
    exportPreparation: SleepExportPreparation?,
    exportResult: SleepExportResult?,
    fileOperationMessage: String?,
    fileOperationError: String?,
    onRequestHealthPermissions: () -> Unit,
    onRequestHistoryPermission: () -> Unit,
    onRequestStatsAllData: () -> Unit,
    onHealthDataRangeChange: (HealthDataRange) -> Unit,
    onImportSleepFiles: () -> Unit,
    onDeleteOwnedSleepRecords: () -> Unit,
    onPrepareSleepExport: (SleepExportRange) -> Unit,
    onCreateSleepExportDocument: (Set<String>) -> Unit,
    onCancelSleepExport: () -> Unit,
): DarkHourAppState {
    var options by remember { mutableStateOf(initialDisplayOptions) }
    var settings by remember { mutableStateOf(initialSettings) }
    var scheduleEntries by remember { mutableStateOf(initialScheduleEntries) }
    var pendingScheduleEditId by remember { mutableStateOf<Long?>(null) }
    var developerAlgorithmId by remember {
        mutableStateOf(initialDeveloperAlgorithmId(BuildConfig.DEBUG))
    }
    var developerOverrides by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var developerInjectedRecords by remember { mutableStateOf<List<SleepRecord>>(emptyList()) }
    var developerInjectionForm by remember { mutableStateOf(defaultDebugSleepInjectionForm(records)) }
    var developerInjectionError by remember { mutableStateOf<String?>(null) }
    var developerInjectionEdited by remember { mutableStateOf(false) }

    LaunchedEffect(records) {
        if (developerInjectedRecords.isEmpty() && !developerInjectionEdited) {
            developerInjectionForm = defaultDebugSleepInjectionForm(records)
        }
    }

    fun addDeveloperSleepRecords() {
        if (!BuildConfig.DEBUG) return
        generateDebugSleepRecords(
            form = developerInjectionForm,
            existingInjectedCount = developerInjectedRecords.size,
        ).fold(
            onSuccess = { result ->
                developerInjectedRecords = developerInjectedRecords + result.records
                developerInjectionForm = result.nextForm
                developerInjectionError = null
                developerInjectionEdited = true
            },
            onFailure = { error -> developerInjectionError = error.message ?: "Invalid injection values" },
        )
    }

    fun clearDeveloperSleepRecords() {
        developerInjectedRecords = emptyList()
        developerInjectionForm = defaultDebugSleepInjectionForm(records)
        developerInjectionError = null
        developerInjectionEdited = false
    }

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
        sleepInjection = DeveloperSleepInjectionState(
            form = developerInjectionForm,
            injectedRecordCount = developerInjectedRecords.size,
            error = developerInjectionError,
            onFormChange = { form ->
                developerInjectionForm = form
                developerInjectionError = null
                developerInjectionEdited = true
            },
            onAdd = ::addDeveloperSleepRecords,
            onClear = ::clearDeveloperSleepRecords,
        ),
    )

    val recordsWithDebugInjection = remember(records, developerInjectedRecords) {
        if (BuildConfig.DEBUG && developerInjectedRecords.isNotEmpty()) {
            (records + developerInjectedRecords).sortedBy(SleepRecord::startTime)
        } else {
            records
        }
    }
    val analysisRecordsWithDebugInjection = remember(analysisRecords, developerInjectedRecords) {
        if (BuildConfig.DEBUG && developerInjectedRecords.isNotEmpty()) {
            (analysisRecords + developerInjectedRecords)
                .distinctBy(SleepRecord::logId)
                .sortedBy(SleepRecord::startTime)
        } else {
            analysisRecords
        }
    }
    val filteredDisplayRecords = remember(recordsWithDebugInjection, settings.includeNaps) {
        if (settings.includeNaps) recordsWithDebugInjection else recordsWithDebugInjection.filter { it.isMainSleep }
    }
    val filteredAnalysisRecords = remember(analysisRecordsWithDebugInjection, settings.includeNaps) {
        if (settings.includeNaps) {
            analysisRecordsWithDebugInjection
        } else {
            analysisRecordsWithDebugInjection.filter { it.isMainSleep }
        }
    }
    val analysis = remember(filteredAnalysisRecords, settings.forecastDays, developerAlgorithmId, developerCircadian.activeOverrides) {
        CircadianAnalyzer.analyze(
            filteredAnalysisRecords,
            extraDays = settings.forecastDays,
            algorithmId = developerAlgorithmId,
            overrides = developerCircadian.activeOverrides,
        )
    }
    val actogramForecastDays = settings.forecastDays.let { days ->
        if (days > 0) days + 1 else 0
    }
    val actogramAnalysis = remember(filteredAnalysisRecords, actogramForecastDays, developerAlgorithmId, developerCircadian.activeOverrides) {
        CircadianAnalyzer.analyze(
            filteredAnalysisRecords,
            extraDays = actogramForecastDays,
            algorithmId = developerAlgorithmId,
            overrides = developerCircadian.activeOverrides,
        )
    }
    val hideActogramForecastTail = actogramAnalysis.days.count { it.isForecast } >
        analysis.days.count { it.isForecast }
    val periodogram = remember(filteredAnalysisRecords) {
        computePeriodogram(buildPeriodogramAnchors(filteredAnalysisRecords))
    }
    val rowHours = when (options.timeScale) {
        ActogramTimeScale.HOURS_24 -> 24.0
        ActogramTimeScale.CIRCADIAN_TAU -> analysis.globalTau
        ActogramTimeScale.CUSTOM -> options.customHours.toDouble()
    }
    val baseLayout = remember(filteredDisplayRecords, actogramAnalysis.days, hideActogramForecastTail, rowHours) {
        ActogramLayoutEngine.build(
            records = filteredDisplayRecords,
            circadianDays = actogramAnalysis.days,
            rowHours = rowHours,
        ).withHiddenActogramForecastTail(hideActogramForecastTail)
    }
    val layout = remember(baseLayout, filteredDisplayRecords, actogramAnalysis.days, scheduleEntries, hideActogramForecastTail, rowHours) {
        if (scheduleEntries.isEmpty()) {
            baseLayout
        } else if (scheduleEntries.any { it.date != null }) {
            ActogramLayoutEngine.build(
                records = filteredDisplayRecords,
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
            records = filteredAnalysisRecords,
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
            statsAllRecords = statsAllRecords?.let { allRecords ->
                if (BuildConfig.DEBUG && developerInjectedRecords.isNotEmpty()) {
                    (allRecords + developerInjectedRecords).distinctBy(SleepRecord::logId).sortedBy(SleepRecord::startTime)
                } else {
                    allRecords
                }
            },
            isRefreshing = isRefreshing,
            isStatsAllDataRefreshing = isStatsAllDataRefreshing,
            importedRecordCount = importedRecordCount,
            expectedRecordCount = expectedRecordCount,
            isImportPartial = isImportPartial,
            importPhase = importPhase,
            importError = importError,
            statsAllDataError = statsAllDataError,
            totalHistoryDays = totalHistoryDays,
            fileWriteSupported = fileWriteSupported,
            fileImportedRecordCount = fileImportedRecordCount,
            fileOperation = fileOperation,
            fileImportResult = fileImportResult,
            exportPreparation = exportPreparation,
            exportResult = exportResult,
            fileOperationMessage = fileOperationMessage,
            fileOperationError = fileOperationError,
            onRequestHealthPermissions = onRequestHealthPermissions,
            onRequestHistoryPermission = onRequestHistoryPermission,
            onRequestStatsAllData = onRequestStatsAllData,
            onDataRangeChange = onHealthDataRangeChange,
            onImportSleepFiles = onImportSleepFiles,
            onDeleteOwnedSleepRecords = onDeleteOwnedSleepRecords,
            onPrepareSleepExport = onPrepareSleepExport,
            onCreateSleepExportDocument = onCreateSleepExportDocument,
            onCancelSleepExport = onCancelSleepExport,
        ),
    )
}

internal fun initialDeveloperAlgorithmId(isDebug: Boolean): String =
    CircadianAlgorithmRegistry.defaultAlgorithm.id

private fun ActogramLayout.withHiddenActogramForecastTail(
    hideForecastTail: Boolean,
): ActogramLayout =
    if (hideForecastTail && rows.isNotEmpty()) {
        copy(hiddenChronologicalTailRows = 1)
    } else {
        this
    }
