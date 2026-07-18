package one.aozora.darkhour.data

import one.aozora.darkhour.core.model.SleepRecord

internal fun HealthConnectUiState.withFileOperationStarted(
    operation: HealthConnectFileOperation,
): HealthConnectUiState = copy(
    fileOperation = operation,
    fileImportResult = null,
    exportResult = null,
    fileOperationMessage = null,
    fileOperationErrorMessage = null,
)

internal fun HealthConnectUiState.withProviderUnavailable(
    access: HealthConnectAccess,
): HealthConnectUiState = copy(
    records = emptyList(),
    recordMetadata = emptyMap(),
    analysisRecords = emptyList(),
    access = access,
    totalHistoryDays = null,
    availableHistoryDays = HealthDataRange.MINIMUM_CUSTOM_DAYS,
    historyPermissionState = HistoryPermissionState.UNAVAILABLE,
    isRefreshing = false,
    importedRecordCount = 0,
    fileImportedRecordCount = 0,
    expectedRecordCount = null,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = null,
)

internal fun HealthConnectUiState.withReadPermissionRequired(
    historyPermissionState: HistoryPermissionState,
): HealthConnectUiState = copy(
    records = emptyList(),
    recordMetadata = emptyMap(),
    analysisRecords = emptyList(),
    access = HealthConnectAccess.PERMISSION_REQUIRED,
    totalHistoryDays = totalHistoryDays.takeIf { historyPermissionState.hasCompleteHistoryAccess },
    historyPermissionState = historyPermissionState,
    isRefreshing = false,
    importedRecordCount = 0,
    fileImportedRecordCount = 0,
    expectedRecordCount = null,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = null,
)

internal fun HealthConnectUiState.withRefreshStarted(
    historyPermissionState: HistoryPermissionState,
): HealthConnectUiState = copy(
    access = HealthConnectAccess.CONNECTED,
    statsAllRecords = statsAllRecords.takeIf { this.historyPermissionState == historyPermissionState },
    historyPermissionState = historyPermissionState,
    isRefreshing = true,
    importedRecordCount = 0,
    expectedRecordCount = null,
    isImportPartial = false,
    importPhase = HealthImportPhase.RECENT,
    errorMessage = null,
)

internal fun HealthConnectUiState.withRefreshCompleted(
    records: List<SleepRecord>,
    recordMetadata: Map<Long, SleepRecordDisplayMetadata>,
    analysisRecords: List<SleepRecord>,
    importedTotalHistoryDays: Int?,
    importedAvailableHistoryDays: Int,
    historyPermissionState: HistoryPermissionState,
    fileImportedRecordCount: Int,
): HealthConnectUiState = copy(
    records = records,
    recordMetadata = recordMetadata,
    analysisRecords = analysisRecords,
    totalHistoryDays = if (historyPermissionState.hasCompleteHistoryAccess) {
        importedTotalHistoryDays ?: totalHistoryDays
    } else {
        null
    },
    availableHistoryDays = if (historyPermissionState.hasCompleteHistoryAccess) {
        importedTotalHistoryDays ?: importedAvailableHistoryDays
    } else {
        maxOf(availableHistoryDays, importedAvailableHistoryDays)
    },
    historyPermissionState = historyPermissionState,
    access = HealthConnectAccess.CONNECTED,
    isRefreshing = false,
    importedRecordCount = records.size,
    fileImportedRecordCount = fileImportedRecordCount,
    expectedRecordCount = records.size,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = null,
)

internal fun HealthConnectUiState.withRefreshFailure(message: String): HealthConnectUiState = copy(
    isRefreshing = false,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = message,
)

internal fun HealthConnectUiState.withImportProgress(
    records: List<SleepRecord>?,
    recordMetadata: Map<Long, SleepRecordDisplayMetadata>?,
    analysisRecords: List<SleepRecord>?,
    importedRecordCount: Int,
    expectedRecordCount: Int?,
    isImportPartial: Boolean,
    phase: HealthImportPhase,
): HealthConnectUiState = copy(
    records = records ?: this.records,
    recordMetadata = recordMetadata ?: this.recordMetadata,
    analysisRecords = analysisRecords ?: this.analysisRecords,
    importedRecordCount = importedRecordCount,
    expectedRecordCount = expectedRecordCount,
    isImportPartial = isImportPartial,
    importPhase = phase,
)

internal fun HealthConnectUiState.withStatsUnavailable(): HealthConnectUiState = copy(
    statsAllRecords = null,
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsPermissionRequired(
    historyPermissionState: HistoryPermissionState,
): HealthConnectUiState = copy(
    statsAllRecords = null,
    historyPermissionState = historyPermissionState,
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshStarted(
    historyPermissionState: HistoryPermissionState,
): HealthConnectUiState = copy(
    historyPermissionState = historyPermissionState,
    isStatsAllDataRefreshing = true,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshCompleted(
    records: List<SleepRecord>,
    importedTotalHistoryDays: Int?,
    importedAvailableHistoryDays: Int,
    historyPermissionState: HistoryPermissionState,
): HealthConnectUiState = copy(
    statsAllRecords = records,
    totalHistoryDays = if (historyPermissionState.hasCompleteHistoryAccess) {
        importedTotalHistoryDays ?: totalHistoryDays
    } else {
        null
    },
    availableHistoryDays = if (historyPermissionState.hasCompleteHistoryAccess) {
        importedTotalHistoryDays ?: importedAvailableHistoryDays
    } else {
        maxOf(availableHistoryDays, importedAvailableHistoryDays)
    },
    historyPermissionState = historyPermissionState,
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshFailure(
    message: String,
): HealthConnectUiState = copy(
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = message,
)
