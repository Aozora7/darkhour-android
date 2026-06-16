package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

fun computeAnchorWeight(record: SleepRecord): Double? {
    val quality = record.sleepScore ?: 0.0
    val durFactor = min(1.0, max(0.0, (record.durationHours - 4.0) / 3.0))
    val weight = quality * durFactor

    if (weight < 0.05) return null

    return if (record.isMainSleep) weight else weight * 0.15
}

fun sleepMidpointHour(record: SleepRecord, globalFirstDateMs: Long): Double {
    val midMs = record.startTime.toEpochMilli() + record.durationMs / 2.0
    return (midMs - globalFirstDateMs) / 3_600_000.0
}

fun prepareAnchors(
    records: List<SleepRecord>,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
): List<CsfAnchor> {
    val candidates = records.mapNotNull { record ->
        val weight = computeAnchorWeight(record) ?: return@mapNotNull null
        record to weight
    }

    val bestByDate = linkedMapOf<java.time.LocalDate, CsfAnchor>()

    for ((record, weight) in candidates) {
        val existing = bestByDate[record.dateOfSleep]
        if (existing == null || weight > existing.weight) {
            bestByDate[record.dateOfSleep] = CsfAnchor(
                dayNumber = daysBetween(globalFirstDate, record.dateOfSleep),
                midpointHour = sleepMidpointHour(record, globalFirstDateMs),
                weight = weight,
                record = record,
            )
        }
    }

    return bestByDate.values.sortedBy { it.dayNumber }
}
