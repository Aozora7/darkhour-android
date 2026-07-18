package one.aozora.darkhour.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.data.HistoryPermissionState
import one.aozora.darkhour.data.HealthConnectFileOperation
import one.aozora.darkhour.data.SleepFileImportResult
import one.aozora.darkhour.data.SleepExportPreparation
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.SleepExportResult
import one.aozora.darkhour.data.SleepRecordDisplayMetadata
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import one.aozora.darkhour.ui.settings.AppSettings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DarkHourApp(
    records: List<SleepRecord>,
    recordMetadata: Map<Long, SleepRecordDisplayMetadata> = emptyMap(),
    modifier: Modifier = Modifier,
    initialSettings: AppSettings = AppSettings(),
    onAppSettingsChange: (AppSettings) -> Unit = {},
    initialDisplayOptions: ActogramDisplayOptions = ActogramDisplayOptions(),
    onDisplayOptionsChange: (ActogramDisplayOptions) -> Unit = {},
    initialScheduleEntries: List<ScheduleEntry> = emptyList(),
    onScheduleEntriesChange: (List<ScheduleEntry>) -> Unit = {},
    healthConnectAccess: HealthConnectAccess = HealthConnectAccess.CONNECTED,
    healthDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    historyPermissionState: HistoryPermissionState = HistoryPermissionState.GRANTED,
    statsAllRecords: List<SleepRecord>? = records,
    isRefreshing: Boolean = false,
    isStatsAllDataRefreshing: Boolean = false,
    importedRecordCount: Int = 0,
    expectedRecordCount: Int? = null,
    isImportPartial: Boolean = false,
    importPhase: HealthImportPhase = HealthImportPhase.IDLE,
    importError: String? = null,
    statsAllDataError: String? = null,
    totalHistoryDays: Int? = null,
    availableHistoryDays: Int = HealthDataRange.MINIMUM_CUSTOM_DAYS,
    fileWriteSupported: Boolean = false,
    fileDeletionSupported: Boolean = fileWriteSupported,
    fileImportedRecordCount: Int = 0,
    fileOperation: HealthConnectFileOperation = HealthConnectFileOperation.IDLE,
    fileImportResult: SleepFileImportResult? = null,
    exportPreparation: SleepExportPreparation? = null,
    exportResult: SleepExportResult? = null,
    fileOperationMessage: String? = null,
    fileOperationError: String? = null,
    onRequestHealthPermissions: () -> Unit = {},
    onRequestHistoryPermission: () -> Unit = {},
    onInstallHealthConnect: () -> Unit = {},
    onOpenHealthConnect: () -> Unit = {},
    onRequestStatsAllData: () -> Unit = {},
    onHealthDataRangeChange: (HealthDataRange) -> Unit = {},
    onImportSleepFiles: () -> Unit = {},
    onDeleteOwnedSleepRecords: () -> Unit = {},
    onPrepareSleepExport: (SleepExportRange) -> Unit = {},
    onCreateSleepExportDocument: (Set<String>) -> Unit = {},
    onCancelSleepExport: () -> Unit = {},
    analysisRecords: List<SleepRecord> = records,
) {
    var pagerScrollBlocked by remember { mutableStateOf(false) }
    val appState = rememberDarkHourAppState(
        records = records,
        recordMetadata = recordMetadata,
        analysisRecords = analysisRecords,
        initialSettings = initialSettings,
        onAppSettingsChange = onAppSettingsChange,
        initialDisplayOptions = initialDisplayOptions,
        onDisplayOptionsChange = onDisplayOptionsChange,
        initialScheduleEntries = initialScheduleEntries,
        onScheduleEntriesChange = onScheduleEntriesChange,
        healthConnectAccess = healthConnectAccess,
        healthDataRange = healthDataRange,
        historyPermissionState = historyPermissionState,
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
        availableHistoryDays = availableHistoryDays,
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
        onHealthDataRangeChange = onHealthDataRangeChange,
        onImportSleepFiles = onImportSleepFiles,
        onDeleteOwnedSleepRecords = onDeleteOwnedSleepRecords,
        onPrepareSleepExport = onPrepareSleepExport,
        onCreateSleepExportDocument = onCreateSleepExportDocument,
        onCancelSleepExport = onCancelSleepExport,
    )

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { DestinationItems.size })
    val scope = rememberCoroutineScope()

    fun selectDestination(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    fun editScheduleEntry(entryId: Long) {
        appState.schedule.onEditEntry(entryId)
        val scheduleIndex = DestinationItems.indexOfFirst {
            it.destination == DarkHourDestination.SCHEDULE
        }
        if (scheduleIndex >= 0) selectDestination(scheduleIndex)
    }

    DarkHourStateProvider(
        sleepAnalysis = appState.sleepAnalysis,
        appSettings = appState.appSettings,
        actogramDisplay = appState.actogramDisplay,
        developerCircadian = appState.developerCircadian,
        schedule = appState.schedule.copy(onEditEntry = ::editScheduleEntry),
        healthConnect = appState.healthConnect,
    ) {
        androidx.compose.material3.Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp

                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        AppNavigationRail(
                            selectedIndex = pagerState.currentPage,
                            onSelected = ::selectDestination,
                        )
                        AppPager(
                            pagerState = pagerState,
                            userScrollEnabled = !pagerScrollBlocked,
                            onTransformingChange = { pagerScrollBlocked = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        bottomBar = {
                            AppNavigationBar(
                                selectedIndex = pagerState.currentPage,
                                pagerState = pagerState,
                                onSelected = ::selectDestination,
                            )
                        },
                    ) { padding ->
                        AppPager(
                            pagerState = pagerState,
                            userScrollEnabled = !pagerScrollBlocked,
                            onTransformingChange = { pagerScrollBlocked = it },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}
