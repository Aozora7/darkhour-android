package one.aozora.darkhour.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal suspend fun HealthConnectClient.readSleepRecords(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    ownedPackageName: String,
    initialImportDuration: Duration = DEFAULT_HISTORY_DURATION,
    readTotalHistoryDays: Boolean = range == HealthDataRange.EntireHistory,
    onProgress: (HealthImportProgress) -> Unit = {},
): ImportedSleepRecords {
    return readSleepRecordsRecentFirst(
        range = range,
        now = now,
        zoneId = zoneId,
        ownedPackageName = ownedPackageName,
        initialImportDuration = initialImportDuration,
        readTotalHistoryDays = readTotalHistoryDays,
        onProgress = onProgress,
    )
}

internal suspend fun HealthConnectClient.readSleepRecordsRecentFirst(
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    ownedPackageName: String,
    initialImportDuration: Duration = DEFAULT_HISTORY_DURATION,
    readTotalHistoryDays: Boolean = range == HealthDataRange.EntireHistory,
    onProgress: (HealthImportProgress) -> Unit = {},
): ImportedSleepRecords {
    val accumulator = ImportedSleepAccumulator(zoneId, ownedPackageName)

    val recentRange = healthConnectInitialReadRange(range, now, initialImportDuration)
    val recentRecords = readSleepRecordsPageRange(recentRange.start, recentRange.end)
    accumulator.add(recentRecords)
    val recentResolved = accumulator.resolvedRecords()
    currentCoroutineContext().ensureActive()
    val oldestAvailableStart = if (readTotalHistoryDays) {
        readOldestSleepRecord(Instant.EPOCH, now)?.startTime
    } else {
        null
    }
    val olderRanges = if (range == HealthDataRange.EntireHistory && oldestAvailableStart == null) {
        emptyList()
    } else {
        healthConnectReadRanges(
            range = range,
            now = now,
            oldestAvailableStart = oldestAvailableStart,
            initialImportDuration = initialImportDuration,
        ).drop(1)
    }
    if (olderRanges.isNotEmpty()) {
        onProgress(
            HealthImportProgress(
                phase = HealthImportPhase.HISTORY,
                records = recentResolved.records,
                analysisRecords = recentResolved.analysisRecords,
                importedRecordCount = recentResolved.records.size,
                expectedRecordCount = null,
                isImportPartial = true,
            ),
        )
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

    val resolved = accumulator.resolvedRecords()
    return ImportedSleepRecords(
        records = resolved.records,
        analysisRecords = resolved.analysisRecords,
        totalHistoryDays = totalHistoryDaysFromOldest(oldestAvailableStart, now, zoneId),
        fileImportedRecordCount = accumulator.fileImportedRecordCount,
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

private const val PAGE_SIZE = 1000
