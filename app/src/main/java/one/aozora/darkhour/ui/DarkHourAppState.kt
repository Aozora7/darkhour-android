package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import one.aozora.darkhour.BuildConfig
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthConnectFileOperation
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.data.SleepExportPreparation
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.SleepExportResult
import one.aozora.darkhour.data.SleepFileImportResult
import one.aozora.darkhour.data.SleepRecordDisplayMetadata
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
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

@Immutable
internal data class DarkHourAppStateInput(
    val records: List<SleepRecord>,
    val recordMetadata: Map<Long, SleepRecordDisplayMetadata>,
    val analysisRecords: List<SleepRecord>,
    val initialSettings: AppSettings,
    val onAppSettingsChange: (AppSettings) -> Unit,
    val initialDisplayOptions: ActogramDisplayOptions,
    val onDisplayOptionsChange: (ActogramDisplayOptions) -> Unit,
    val initialScheduleEntries: List<ScheduleEntry>,
    val onScheduleEntriesChange: (List<ScheduleEntry>) -> Unit,
    val healthConnect: HealthConnectState,
)

/**
 * Compatibility entry point retained for previews, tests, and external callers. Internally the
 * host values are grouped immediately so feature-specific state does not depend on this flat API.
 */
@Composable
fun rememberDarkHourAppState(
    records: List<SleepRecord>,
    recordMetadata: Map<Long, SleepRecordDisplayMetadata>,
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
    fileDeletionSupported: Boolean,
    fileImportedRecordCount: Int,
    fileOperation: HealthConnectFileOperation,
    fileImportResult: SleepFileImportResult?,
    exportPreparation: SleepExportPreparation?,
    exportResult: SleepExportResult?,
    fileOperationMessage: String?,
    fileOperationError: String?,
    onRequestHealthPermissions: () -> Unit,
    onRequestHistoryPermission: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onRequestStatsAllData: () -> Unit,
    onHealthDataRangeChange: (HealthDataRange) -> Unit,
    onImportSleepFiles: () -> Unit,
    onDeleteOwnedSleepRecords: () -> Unit,
    onPrepareSleepExport: (SleepExportRange) -> Unit,
    onCreateSleepExportDocument: (Set<String>) -> Unit,
    onCancelSleepExport: () -> Unit,
): DarkHourAppState = rememberDarkHourAppState(
    DarkHourAppStateInput(
        records = records,
        recordMetadata = recordMetadata,
        analysisRecords = analysisRecords,
        initialSettings = initialSettings,
        onAppSettingsChange = onAppSettingsChange,
        initialDisplayOptions = initialDisplayOptions,
        onDisplayOptionsChange = onDisplayOptionsChange,
        initialScheduleEntries = initialScheduleEntries,
        onScheduleEntriesChange = onScheduleEntriesChange,
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
            fileWriteSupported = fileWriteSupported,
            fileDeletionSupported = fileDeletionSupported,
            fileImportedRecordCount = fileImportedRecordCount,
            fileOperation = fileOperation,
            fileImportResult = fileImportResult,
            exportPreparation = exportPreparation,
            exportResult = exportResult,
            fileOperationMessage = fileOperationMessage,
            fileOperationError = fileOperationError,
            onRequestHealthPermissions = onRequestHealthPermissions,
            onRequestHistoryPermission = onRequestHistoryPermission,
            onInstallHealthConnect = onInstallHealthConnect,
            onOpenHealthConnect = onOpenHealthConnect,
            onRequestStatsAllData = onRequestStatsAllData,
            onDataRangeChange = onHealthDataRangeChange,
            onImportSleepFiles = onImportSleepFiles,
            onDeleteOwnedSleepRecords = onDeleteOwnedSleepRecords,
            onPrepareSleepExport = onPrepareSleepExport,
            onCreateSleepExportDocument = onCreateSleepExportDocument,
            onCancelSleepExport = onCancelSleepExport,
        ),
    ),
)

@Composable
internal fun rememberDarkHourAppState(input: DarkHourAppStateInput): DarkHourAppState {
    val userState = rememberUserState(input)
    val developerSession = rememberDeveloperCircadianSession(
        records = input.records,
        isDebug = BuildConfig.DEBUG,
    )
    val displayRecords = remember(input.records, developerSession.injectedRecords) {
        input.records.withDeveloperDisplayRecords(
            developerSession.injectedRecords,
            BuildConfig.DEBUG,
        )
    }
    val analysisRecords = remember(input.analysisRecords, developerSession.injectedRecords) {
        input.analysisRecords.withDeveloperAnalysisRecords(
            developerSession.injectedRecords,
            BuildConfig.DEBUG,
        )
    }
    val derivedAnalysis = rememberDerivedSleepAnalysis(
        displayRecords = displayRecords,
        analysisRecords = analysisRecords,
        settings = userState.appSettings.settings,
        developerState = developerSession.state,
    )
    val actogramDisplay = rememberActogramDisplayState(
        records = derivedAnalysis.displayRecords,
        analysis = derivedAnalysis.actogramAnalysis,
        hideForecastTail = derivedAnalysis.hideActogramForecastTail,
        options = userState.options,
        scheduleEntries = userState.schedule.entries,
        recordMetadata = input.recordMetadata,
        onOptionsChange = userState.onOptionsChange,
    )
    val statsAllRecords = input.healthConnect.statsAllRecords?.withDeveloperAnalysisRecords(
        developerSession.injectedRecords,
        BuildConfig.DEBUG,
    )

    return DarkHourAppState(
        sleepAnalysis = derivedAnalysis.state,
        appSettings = userState.appSettings,
        actogramDisplay = actogramDisplay,
        developerCircadian = developerSession.state,
        schedule = userState.schedule,
        healthConnect = input.healthConnect.copy(statsAllRecords = statsAllRecords),
    )
}

internal data class RememberedUserState(
    val appSettings: AppSettingsState,
    val options: ActogramDisplayOptions,
    val onOptionsChange: (ActogramDisplayOptions) -> Unit,
    val schedule: ScheduleState,
)

@Composable
private fun rememberUserState(input: DarkHourAppStateInput): RememberedUserState {
    var settings by remember { mutableStateOf(input.initialSettings) }
    var options by remember { mutableStateOf(input.initialDisplayOptions) }
    var scheduleEntries by remember { mutableStateOf(input.initialScheduleEntries) }
    var pendingScheduleEditId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(options) {
        delay(300.milliseconds)
        input.onDisplayOptionsChange(options)
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        input.onAppSettingsChange(updated)
    }

    fun updateOptions(updated: ActogramDisplayOptions) {
        options = updated
    }

    fun updateScheduleEntries(updated: List<ScheduleEntry>) {
        scheduleEntries = updated
        input.onScheduleEntriesChange(updated)
    }

    return RememberedUserState(
        appSettings = AppSettingsState(
            settings = settings,
            onSettingsChange = ::updateSettings,
        ),
        options = options,
        onOptionsChange = ::updateOptions,
        schedule = ScheduleState(
            entries = scheduleEntries,
            pendingEditId = pendingScheduleEditId,
            onEntriesChange = ::updateScheduleEntries,
            onEditConsumed = { pendingScheduleEditId = null },
            onEditEntry = { entryId -> pendingScheduleEditId = entryId },
        ),
    )
}
