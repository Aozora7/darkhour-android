package one.aozora.darkhour.data

import androidx.health.connect.client.records.SleepSessionRecord
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun importSleepRecords(
    rawRecords: List<SleepSessionRecord>,
    range: HealthDataRange,
    now: Instant,
    zoneId: ZoneId,
    totalHistoryComplete: Boolean = true,
): ImportedSleepRecords {
    val totalHistoryDays = if (totalHistoryComplete) {
        totalHistoryDaysFromOldest(rawRecords.minOfOrNull { it.startTime }, now, zoneId)
    } else {
        null
    }
    val imported = rawRecords
        .asSequence()
        .filter { it.isInRange(range, now) }
        .map { it.toImportedSleepRecord(zoneId) }
        .toList()
    val resolved = resolveImportedSleepRecords(imported)
    return ImportedSleepRecords(
        records = resolved.records,
        analysisRecords = resolved.analysisRecords,
        totalHistoryDays = totalHistoryDays,
    )
}

internal data class ImportedSleepRecords(
    val records: List<ImportedSleepRecord>,
    val analysisRecords: List<ImportedSleepRecord>,
    val totalHistoryDays: Int?,
)

internal data class HealthImportProgress(
    val phase: HealthImportPhase,
    val records: List<ImportedSleepRecord>? = null,
    val analysisRecords: List<ImportedSleepRecord>? = null,
    val importedRecordCount: Int,
    val expectedRecordCount: Int?,
    val isImportPartial: Boolean,
)

internal class ImportedSleepAccumulator(
    private val zoneId: ZoneId,
) {
    private val recordsByIdentity = LinkedHashMap<Any, ImportedSleepRecord>()

    val size: Int
        get() = resolvedRecords().records.size

    fun add(rawRecords: List<SleepSessionRecord>) {
        rawRecords.forEach { rawRecord ->
            val imported = rawRecord.toImportedSleepRecord(zoneId)
            val identity = imported.deduplicationIdentity()
            recordsByIdentity[identity] = recordsByIdentity[identity]
                ?.let { existing -> preferredImportedSleepRecord(existing, imported) }
                ?: imported
        }
    }

    fun resolvedRecords(): ResolvedImportedSleepRecords =
        resolveImportedSleepRecords(recordsByIdentity.values.toList())

    fun sortedRecords(): List<ImportedSleepRecord> = resolvedRecords().records
}

internal fun totalHistoryDaysFromOldest(
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
