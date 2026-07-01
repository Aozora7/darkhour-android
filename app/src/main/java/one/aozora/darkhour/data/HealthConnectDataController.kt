package one.aozora.darkhour.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

class HealthConnectDataController(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    initialDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    initialImportDuration: Duration = DEFAULT_HISTORY_DURATION,
) {
    private val applicationContext = context.applicationContext
    private val zoneId = ZoneId.systemDefault()
    private var recentImportDuration = initialImportDuration.coerceAtLeast(Duration.ofDays(1))
    private var refreshJob: Job? = null
    private var statsAllDataRefreshJob: Job? = null
    private val mutableState = MutableStateFlow(
        HealthConnectUiState(dataRange = initialDataRange),
    )

    val state: StateFlow<HealthConnectUiState> = mutableState.asStateFlow()

    fun requiredPermissions(range: HealthDataRange = state.value.dataRange): Set<String> = buildSet {
        add(SLEEP_READ_PERMISSION)
        if (range.requiresHistoryPermission) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    fun setDataRange(range: HealthDataRange) {
        if (range == state.value.dataRange) return
        mutableState.value = state.value.copy(dataRange = range, errorMessage = null)
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val range = state.value.dataRange
                when (HealthConnectClient.getSdkStatus(applicationContext)) {
                    HealthConnectClient.SDK_UNAVAILABLE -> {
                        mutableState.value = state.value.copy(
                            records = emptyList(),
                            access = HealthConnectAccess.UNAVAILABLE,
                            totalHistoryDays = null,
                            hasHistoryPermission = false,
                            isRefreshing = false,
                            importedRecordCount = 0,
                            expectedRecordCount = null,
                            isImportPartial = false,
                            importPhase = HealthImportPhase.IDLE,
                            errorMessage = null,
                        )
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        mutableState.value = state.value.copy(
                            records = emptyList(),
                            access = HealthConnectAccess.UPDATE_REQUIRED,
                            totalHistoryDays = null,
                            hasHistoryPermission = false,
                            isRefreshing = false,
                            importedRecordCount = 0,
                            expectedRecordCount = null,
                            isImportPartial = false,
                            importPhase = HealthImportPhase.IDLE,
                            errorMessage = null,
                        )
                    }
                    HealthConnectClient.SDK_AVAILABLE -> refreshAvailableClient(range)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(
                    isRefreshing = false,
                    errorMessage = failure.message ?: "Health Connect refresh failed",
                )
            }
        }
    }

    fun refreshStatsAllData() {
        statsAllDataRefreshJob?.cancel()
        statsAllDataRefreshJob = scope.launch {
            try {
                when (HealthConnectClient.getSdkStatus(applicationContext)) {
                    HealthConnectClient.SDK_UNAVAILABLE,
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        mutableState.value = state.value.copy(
                            statsAllRecords = null,
                            isStatsAllDataRefreshing = false,
                            statsAllDataErrorMessage = null,
                        )
                    }
                    HealthConnectClient.SDK_AVAILABLE -> refreshStatsAllDataAvailableClient()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(
                    isStatsAllDataRefreshing = false,
                    statsAllDataErrorMessage = failure.message ?: "Stats import failed",
                )
            }
        }
    }

    private suspend fun refreshAvailableClient(range: HealthDataRange) {
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        val hasHistoryPermission =
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        if (!granted.containsAll(requiredPermissions(range))) {
            mutableState.value = state.value.copy(
                records = emptyList(),
                access = HealthConnectAccess.PERMISSION_REQUIRED,
                totalHistoryDays = if (hasHistoryPermission) state.value.totalHistoryDays else null,
                hasHistoryPermission = hasHistoryPermission,
                isRefreshing = false,
                importedRecordCount = 0,
                expectedRecordCount = null,
                isImportPartial = false,
                importPhase = HealthImportPhase.IDLE,
                errorMessage = null,
            )
            return
        }

        mutableState.value = state.value.copy(
            access = HealthConnectAccess.CONNECTED,
            hasHistoryPermission = hasHistoryPermission,
            isRefreshing = true,
            importedRecordCount = 0,
            expectedRecordCount = null,
            isImportPartial = false,
            importPhase = HealthImportPhase.RECENT,
            errorMessage = null,
        )
        try {
            val imported = withContext(Dispatchers.IO) {
                client.readSleepRecords(
                    range = range,
                    now = clock.instant(),
                    zoneId = zoneId,
                    initialImportDuration = recentImportDuration,
                    readTotalHistoryDays = hasHistoryPermission,
                    onProgress = ::publishImportProgress,
                )
            }
            mutableState.value = state.value.copy(
                records = imported.records.map(ImportedSleepRecord::record).sortedBy(SleepRecord::startTime),
                totalHistoryDays = if (hasHistoryPermission) {
                    imported.totalHistoryDays ?: state.value.totalHistoryDays
                } else {
                    null
                },
                access = HealthConnectAccess.CONNECTED,
                isRefreshing = false,
                importedRecordCount = imported.records.size,
                expectedRecordCount = imported.records.size,
                isImportPartial = false,
                importPhase = HealthImportPhase.IDLE,
                errorMessage = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(
                isRefreshing = false,
                isImportPartial = false,
                importPhase = HealthImportPhase.IDLE,
                errorMessage = failure.message ?: "Health Connect import failed",
            )
        }
    }

    private suspend fun refreshStatsAllDataAvailableClient() {
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(requiredPermissions(HealthDataRange.ENTIRE_HISTORY))) {
            mutableState.value = state.value.copy(
                statsAllRecords = null,
                hasHistoryPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted,
                isStatsAllDataRefreshing = false,
                statsAllDataErrorMessage = null,
            )
            return
        }

        mutableState.value = state.value.copy(
            hasHistoryPermission = true,
            isStatsAllDataRefreshing = true,
            statsAllDataErrorMessage = null,
        )
        try {
            val imported = withContext(Dispatchers.IO) {
                client.readSleepRecords(
                    range = HealthDataRange.ENTIRE_HISTORY,
                    now = clock.instant(),
                    zoneId = zoneId,
                    initialImportDuration = recentImportDuration,
                    readTotalHistoryDays = true,
                )
            }
            mutableState.value = state.value.copy(
                statsAllRecords = imported.records.map(ImportedSleepRecord::record).sortedBy(SleepRecord::startTime),
                totalHistoryDays = imported.totalHistoryDays ?: state.value.totalHistoryDays,
                hasHistoryPermission = true,
                isStatsAllDataRefreshing = false,
                statsAllDataErrorMessage = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(
                isStatsAllDataRefreshing = false,
                statsAllDataErrorMessage = failure.message ?: "Stats import failed",
            )
        }
    }

    private fun publishImportProgress(progress: HealthImportProgress) {
        val progressRecords = progress.records
            ?.map(ImportedSleepRecord::record)
            ?.sortedBy(SleepRecord::startTime)
        mutableState.value = state.value.copy(
            records = progressRecords ?: state.value.records,
            importedRecordCount = progress.importedRecordCount,
            expectedRecordCount = progress.expectedRecordCount,
            isImportPartial = progress.isImportPartial,
            importPhase = progress.phase,
        )
    }

    companion object {
        val permissionContract
            get() = PermissionController.createRequestPermissionResultContract()

        private val SLEEP_READ_PERMISSION =
            HealthPermission.getReadPermission(SleepSessionRecord::class)
    }
}
