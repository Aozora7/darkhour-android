package one.aozora.darkhour.data

import one.aozora.darkhour.core.model.SleepRecord

sealed class HealthDataRange {
    data object DefaultPeriod : HealthDataRange()
    data object EntireHistory : HealthDataRange()
    data class Custom(val days: Int) : HealthDataRange()

    val requiresHistoryPermission: Boolean
        get() = this == EntireHistory || (this is Custom && days > MINIMUM_CUSTOM_DAYS)

    companion object {
        val DEFAULT_PERIOD: HealthDataRange = DefaultPeriod
        val ENTIRE_HISTORY: HealthDataRange = EntireHistory
        const val MINIMUM_CUSTOM_DAYS = 30
        const val DEFAULT_CUSTOM_DAYS = 90

        fun custom(days: Int): HealthDataRange =
            Custom(days.coerceAtLeast(MINIMUM_CUSTOM_DAYS))
    }
}

enum class HealthConnectAccess {
    CONNECTED,
    PERMISSION_REQUIRED,
    SETUP_REQUIRED,
    UNAVAILABLE,
    INSTALL_REQUIRED,
    UPDATE_REQUIRED;

    val providerAvailable: Boolean
        get() = this == CONNECTED || this == PERMISSION_REQUIRED
}

enum class HealthImportPhase {
    IDLE,
    RECENT,
    HISTORY,
}

data class HealthConnectUiState(
    val records: List<SleepRecord> = emptyList(),
    val analysisRecords: List<SleepRecord> = records,
    val statsAllRecords: List<SleepRecord>? = null,
    val access: HealthConnectAccess = HealthConnectAccess.UNAVAILABLE,
    val dataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    val totalHistoryDays: Int? = null,
    val hasHistoryPermission: Boolean = false,
    val isRefreshing: Boolean = false,
    val isStatsAllDataRefreshing: Boolean = false,
    val importedRecordCount: Int = 0,
    val expectedRecordCount: Int? = null,
    val isImportPartial: Boolean = false,
    val importPhase: HealthImportPhase = HealthImportPhase.IDLE,
    val errorMessage: String? = null,
    val statsAllDataErrorMessage: String? = null,
    val fileWriteSupported: Boolean = false,
    val fileDeletionSupported: Boolean = fileWriteSupported,
    val fileImportedRecordCount: Int = 0,
    val fileOperation: HealthConnectFileOperation = HealthConnectFileOperation.IDLE,
    val fileImportResult: SleepFileImportResult? = null,
    val exportPreparation: SleepExportPreparation? = null,
    val exportResult: SleepExportResult? = null,
    val fileOperationMessage: String? = null,
    val fileOperationErrorMessage: String? = null,
)
