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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

enum class HealthImportPhase {
    IDLE,
    RECENT,
    HISTORY,
}

data class HealthConnectUiState(
    val records: List<SleepRecord> = emptyList(),
    val access: HealthConnectAccess = HealthConnectAccess.UNAVAILABLE,
    val dataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    val totalHistoryDays: Int? = null,
    val hasHistoryPermission: Boolean = false,
    val isRefreshing: Boolean = false,
    val importedRecordCount: Int = 0,
    val expectedRecordCount: Int? = null,
    val isImportPartial: Boolean = false,
    val importPhase: HealthImportPhase = HealthImportPhase.IDLE,
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
                    onProgress = ::publishImportProgress,
                )
            }
            mutableState.value = state.value.copy(
                records = imported.records.map(ImportedSleepRecord::record).sortedBy(SleepRecord::startTime),
                totalHistoryDays = imported.totalHistoryDays ?: state.value.totalHistoryDays,
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

internal suspend fun HealthConnectClient.readSleepRecords(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    onProgress: (HealthImportProgress) -> Unit = {},
): ImportedSleepRecords {
    if (!range.requiresHistoryPermission) {
        val queryStart = now.minus(DEFAULT_HISTORY_DURATION)
        val rawRecords = readSleepRecordsPageRange(queryStart, now)
        return importSleepRecords(
            rawRecords = rawRecords,
            range = range,
            now = now,
            zoneId = zoneId,
            totalHistoryComplete = false,
        )
    }

    return readSleepRecordsRecentFirst(
        range = range,
        now = now,
        zoneId = zoneId,
        onProgress = onProgress,
    )
}

internal suspend fun HealthConnectClient.readSleepRecordsRecentFirst(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    onProgress: (HealthImportProgress) -> Unit = {},
): ImportedSleepRecords {
    val accumulator = ImportedSleepAccumulator(zoneId)

    val recentRange = HealthConnectReadRange(now.minus(DEFAULT_HISTORY_DURATION), now)
    val recentRecords = readSleepRecordsPageRange(recentRange.start, recentRange.end)
    accumulator.add(recentRecords)
    currentCoroutineContext().ensureActive()
    onProgress(
        HealthImportProgress(
            phase = HealthImportPhase.HISTORY,
            records = accumulator.sortedRecords(),
            importedRecordCount = accumulator.size,
            expectedRecordCount = null,
            isImportPartial = true,
        ),
    )

    val oldestAvailableStart = readOldestSleepRecord(Instant.EPOCH, now)?.startTime
    val olderRanges = if (range == HealthDataRange.EntireHistory && oldestAvailableStart == null) {
        emptyList()
    } else {
        healthConnectReadRanges(
            range = range,
            now = now,
            oldestAvailableStart = oldestAvailableStart,
        ).drop(1)
    }
    olderRanges.forEachIndexed { index, readRange ->
        currentCoroutineContext().ensureActive()
        val rawRecords = readSleepRecordsPageRange(readRange.start, readRange.end)
        accumulator.add(rawRecords)
        currentCoroutineContext().ensureActive()
        onProgress(
            HealthImportProgress(
                phase = HealthImportPhase.HISTORY,
                records = null,
                importedRecordCount = accumulator.size,
                expectedRecordCount = null,
                isImportPartial = index < olderRanges.lastIndex,
            ),
        )
    }

    return ImportedSleepRecords(
        records = accumulator.sortedRecords(),
        totalHistoryDays = totalHistoryDaysFromOldest(
            oldest = oldestAvailableStart ?: accumulator.oldestStartTime(),
            now = now,
            zoneId = zoneId,
        ),
    )
}

internal suspend fun HealthConnectClient.readOldestSleepRecord(
    start: Instant,
    end: Instant,
): SleepSessionRecord? {
    if (start >= end) return null
    return readRecords(
        ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end),
            ascendingOrder = true,
            pageSize = 1,
        ),
    ).records.firstOrNull()
}

internal suspend fun HealthConnectClient.readSleepRecordsPageRange(
    start: Instant,
    end: Instant,
): List<SleepSessionRecord> {
    val rawRecords = mutableListOf<SleepSessionRecord>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
                pageSize = PAGE_SIZE,
                pageToken = pageToken,
            ),
        )
        rawRecords += response.records
        pageToken = response.pageToken
    } while (pageToken != null)
    return rawRecords
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

internal data class HealthImportProgress(
    val phase: HealthImportPhase,
    val records: List<ImportedSleepRecord>? = null,
    val importedRecordCount: Int,
    val expectedRecordCount: Int?,
    val isImportPartial: Boolean,
)

internal data class HealthConnectReadRange(
    val start: Instant,
    val end: Instant,
)

internal fun healthConnectReadRanges(
    range: HealthDataRange,
    now: Instant,
    oldestAvailableStart: Instant? = null,
): List<HealthConnectReadRange> {
    val recentStart = now.minus(DEFAULT_HISTORY_DURATION)
    if (!range.requiresHistoryPermission) {
        return listOf(HealthConnectReadRange(recentStart, now))
    }

    val oldestStart = when (range) {
        HealthDataRange.EntireHistory -> oldestAvailableStart ?: Instant.EPOCH
        is HealthDataRange.Custom -> now.minus(Duration.ofDays(range.days.toLong()))
        HealthDataRange.DefaultPeriod -> recentStart
    }
    if (oldestStart >= recentStart) {
        return listOf(HealthConnectReadRange(oldestStart, now))
    }

    val ranges = mutableListOf(HealthConnectReadRange(recentStart, now))
    var chunkEnd = recentStart
    while (chunkEnd > oldestStart) {
        val chunkStart = maxOf(oldestStart, chunkEnd.minus(OLDER_HISTORY_CHUNK_DURATION))
        ranges += HealthConnectReadRange(chunkStart, chunkEnd)
        chunkEnd = chunkStart
    }
    return ranges
}

internal class ImportedSleepAccumulator(
    private val zoneId: ZoneId,
) {
    private val recordsByIdentity = LinkedHashMap<Any, ImportedSleepRecord>()

    val size: Int
        get() = recordsByIdentity.size

    fun add(rawRecords: List<SleepSessionRecord>) {
        rawRecords.forEach { rawRecord ->
            val imported = rawRecord.toImportedSleepRecord(zoneId)
            recordsByIdentity[imported.deduplicationIdentity()] = imported
        }
    }

    fun sortedRecords(): List<ImportedSleepRecord> =
        recordsByIdentity.values.sortedBy { it.record.startTime }

    fun totalHistoryDays(now: Instant, zoneId: ZoneId): Int? {
        return totalHistoryDaysFromOldest(oldestStartTime(), now, zoneId)
    }

    fun oldestStartTime(): Instant? =
        recordsByIdentity.values.minOfOrNull { it.record.startTime }
}

private fun ImportedSleepRecord.deduplicationIdentity(): Any =
    sourceRecordId ?: record.logId

private fun List<SleepSessionRecord>.totalHistoryDays(now: Instant, zoneId: ZoneId): Int? {
    val oldest = minOfOrNull { it.startTime } ?: return null
    return totalHistoryDaysFromOldest(oldest, now, zoneId)
}

private fun totalHistoryDaysFromOldest(
    oldest: Instant?,
    now: Instant,
    zoneId: ZoneId,
): Int? {
    if (oldest == null) return null
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
private val OLDER_HISTORY_CHUNK_DURATION = Duration.ofDays(180)
private const val PAGE_SIZE = 1000
