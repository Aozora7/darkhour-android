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
    hasHistoryPermission = false,
    isRefreshing = false,
    importedRecordCount = 0,
    fileImportedRecordCount = 0,
    expectedRecordCount = null,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = null,
)

internal fun HealthConnectUiState.withReadPermissionRequired(
    hasHistoryPermission: Boolean,
): HealthConnectUiState = copy(
    records = emptyList(),
    recordMetadata = emptyMap(),
    analysisRecords = emptyList(),
    access = HealthConnectAccess.PERMISSION_REQUIRED,
    totalHistoryDays = totalHistoryDays.takeIf { hasHistoryPermission },
    hasHistoryPermission = hasHistoryPermission,
    isRefreshing = false,
    importedRecordCount = 0,
    fileImportedRecordCount = 0,
    expectedRecordCount = null,
    isImportPartial = false,
    importPhase = HealthImportPhase.IDLE,
    errorMessage = null,
)

internal fun HealthConnectUiState.withRefreshStarted(
    hasHistoryPermission: Boolean,
): HealthConnectUiState = copy(
    access = HealthConnectAccess.CONNECTED,
    hasHistoryPermission = hasHistoryPermission,
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
    hasHistoryPermission: Boolean,
    fileImportedRecordCount: Int,
): HealthConnectUiState = copy(
    records = records,
    recordMetadata = recordMetadata,
    analysisRecords = analysisRecords,
    totalHistoryDays = if (hasHistoryPermission) {
        importedTotalHistoryDays ?: totalHistoryDays
    } else {
        null
    },
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
    hasHistoryPermission: Boolean,
): HealthConnectUiState = copy(
    statsAllRecords = null,
    hasHistoryPermission = hasHistoryPermission,
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshStarted(): HealthConnectUiState = copy(
    hasHistoryPermission = true,
    isStatsAllDataRefreshing = true,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshCompleted(
    records: List<SleepRecord>,
    importedTotalHistoryDays: Int?,
): HealthConnectUiState = copy(
    statsAllRecords = records,
    totalHistoryDays = importedTotalHistoryDays ?: totalHistoryDays,
    hasHistoryPermission = true,
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = null,
)

internal fun HealthConnectUiState.withStatsRefreshFailure(
    message: String,
): HealthConnectUiState = copy(
    isStatsAllDataRefreshing = false,
    statsAllDataErrorMessage = message,
)
