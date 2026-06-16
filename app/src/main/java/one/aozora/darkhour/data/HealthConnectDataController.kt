package one.aozora.darkhour.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class HealthDataRange {
    DEFAULT_PERIOD,
    ENTIRE_HISTORY,
}

enum class HealthConnectAccess {
    CONNECTED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    UPDATE_REQUIRED,
}

data class HealthConnectUiState(
    val records: List<SleepRecord> = emptyList(),
    val access: HealthConnectAccess = HealthConnectAccess.UNAVAILABLE,
    val dataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    val hasHistoryPermission: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class HealthConnectDataController(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val zoneId = ZoneId.systemDefault()
    private var refreshJob: Job? = null
    private val mutableState = MutableStateFlow(
        HealthConnectUiState(dataRange = preferences.readDataRange()),
    )

    val state: StateFlow<HealthConnectUiState> = mutableState.asStateFlow()

    fun requiredPermissions(range: HealthDataRange = state.value.dataRange): Set<String> = buildSet {
        add(SLEEP_READ_PERMISSION)
        if (range == HealthDataRange.ENTIRE_HISTORY) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    fun setDataRange(range: HealthDataRange) {
        if (range == state.value.dataRange) return
        preferences.edit().putString(DATA_RANGE_KEY, range.name).apply()
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
                            hasHistoryPermission = false,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        mutableState.value = state.value.copy(
                            records = emptyList(),
                            access = HealthConnectAccess.UPDATE_REQUIRED,
                            hasHistoryPermission = false,
                            isRefreshing = false,
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

    private suspend fun refreshAvailableClient(range: HealthDataRange) {
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        val hasHistoryPermission =
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        if (!granted.containsAll(requiredPermissions(range))) {
            mutableState.value = state.value.copy(
                records = emptyList(),
                access = HealthConnectAccess.PERMISSION_REQUIRED,
                hasHistoryPermission = hasHistoryPermission,
                isRefreshing = false,
                errorMessage = null,
            )
            return
        }

        mutableState.value = state.value.copy(
            access = HealthConnectAccess.CONNECTED,
            hasHistoryPermission = hasHistoryPermission,
            isRefreshing = true,
            errorMessage = null,
        )
        runCatching {
            client.readSleepRecords(range, clock.instant(), zoneId)
        }.onSuccess { imported ->
            mutableState.value = state.value.copy(
                records = imported.map(ImportedSleepRecord::record).sortedBy(SleepRecord::startTime),
                access = HealthConnectAccess.CONNECTED,
                isRefreshing = false,
                errorMessage = null,
            )
        }.onFailure { failure ->
            mutableState.value = state.value.copy(
                isRefreshing = false,
                errorMessage = failure.message ?: "Health Connect import failed",
            )
        }
    }

    companion object {
        val permissionContract
            get() = PermissionController.createRequestPermissionResultContract()

        private val SLEEP_READ_PERMISSION =
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        private const val PREFERENCES_NAME = "health_connect"
        private const val DATA_RANGE_KEY = "data_range"
    }
}

internal suspend fun HealthConnectClient.readSleepRecords(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
): List<ImportedSleepRecord> {
    val start = when (range) {
        HealthDataRange.DEFAULT_PERIOD -> now.minus(DEFAULT_HISTORY_DURATION)
        HealthDataRange.ENTIRE_HISTORY -> Instant.EPOCH
    }
    val imported = mutableListOf<ImportedSleepRecord>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, now),
                ascendingOrder = true,
                pageSize = PAGE_SIZE,
                pageToken = pageToken,
            ),
        )
        imported += response.records.map { it.toImportedSleepRecord(zoneId) }
        pageToken = response.pageToken
    } while (pageToken != null)
    return imported.distinctBy { it.sourceRecordId ?: it.record.logId }
}

private fun android.content.SharedPreferences.readDataRange(): HealthDataRange =
    getString("data_range", null)
        ?.let { saved -> HealthDataRange.entries.firstOrNull { it.name == saved } }
        ?: HealthDataRange.DEFAULT_PERIOD

private val DEFAULT_HISTORY_DURATION = Duration.ofDays(30)
private const val PAGE_SIZE = 1000
