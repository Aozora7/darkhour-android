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
import java.time.temporal.ChronoUnit
import java.time.ZoneId

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
    UNAVAILABLE,
    UPDATE_REQUIRED,
}

data class HealthConnectUiState(
    val records: List<SleepRecord> = emptyList(),
    val access: HealthConnectAccess = HealthConnectAccess.UNAVAILABLE,
    val dataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    val totalHistoryDays: Int? = null,
    val hasHistoryPermission: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class HealthConnectDataController(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    initialDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
) {
    private val applicationContext = context.applicationContext
    private val zoneId = ZoneId.systemDefault()
    private var refreshJob: Job? = null
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
                totalHistoryDays = if (hasHistoryPermission) state.value.totalHistoryDays else null,
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
                records = imported.records.map(ImportedSleepRecord::record).sortedBy(SleepRecord::startTime),
                totalHistoryDays = imported.totalHistoryDays ?: state.value.totalHistoryDays,
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
    }
}

internal suspend fun HealthConnectClient.readSleepRecords(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
): ImportedSleepRecords {
    val queryStart = when (range) {
        HealthDataRange.DefaultPeriod -> now.minus(DEFAULT_HISTORY_DURATION)
        HealthDataRange.EntireHistory,
        is HealthDataRange.Custom -> if (range.requiresHistoryPermission) {
            Instant.EPOCH
        } else {
            now.minus(DEFAULT_HISTORY_DURATION)
        }
    }
    val rawRecords = mutableListOf<SleepSessionRecord>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(queryStart, now),
                ascendingOrder = true,
                pageSize = PAGE_SIZE,
                pageToken = pageToken,
            ),
        )
        rawRecords += response.records
        pageToken = response.pageToken
    } while (pageToken != null)

    return importSleepRecords(
        rawRecords = rawRecords,
        range = range,
        now = now,
        zoneId = zoneId,
        totalHistoryComplete = queryStart == Instant.EPOCH,
    )
}

internal fun importSleepRecords(
    rawRecords: List<SleepSessionRecord>,
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    totalHistoryComplete: Boolean = true,
): ImportedSleepRecords {
    val totalHistoryDays = if (totalHistoryComplete) {
        rawRecords.totalHistoryDays(now, zoneId)
    } else {
        null
    }
    val imported = rawRecords
        .asSequence()
        .filter { it.isInRange(range, now) }
        .map { it.toImportedSleepRecord(zoneId) }
        .distinctBy { it.sourceRecordId ?: it.record.logId }
        .toList()
    return ImportedSleepRecords(imported, totalHistoryDays)
}

internal data class ImportedSleepRecords(
    val records: List<ImportedSleepRecord>,
    val totalHistoryDays: Int?,
)

private fun List<SleepSessionRecord>.totalHistoryDays(now: Instant, zoneId: ZoneId): Int? {
    val oldest = minOfOrNull { it.startTime } ?: return null
    val oldestDate = oldest.atZone(zoneId).toLocalDate()
    val currentDate = now.atZone(zoneId).toLocalDate()
    return (ChronoUnit.DAYS.between(oldestDate, currentDate) + 1)
        .coerceAtLeast(HealthDataRange.MINIMUM_CUSTOM_DAYS.toLong())
        .toInt()
}

private fun SleepSessionRecord.isInRange(range: HealthDataRange, now: Instant): Boolean {
    val filterStart = when (range) {
        HealthDataRange.DefaultPeriod -> now.minus(DEFAULT_HISTORY_DURATION)
        HealthDataRange.EntireHistory -> Instant.EPOCH
        is HealthDataRange.Custom -> now.minus(Duration.ofDays(range.days.toLong()))
    }
    return endTime > filterStart && startTime < now
}

private val DEFAULT_HISTORY_DURATION = Duration.ofDays(30)
private const val PAGE_SIZE = 1000
