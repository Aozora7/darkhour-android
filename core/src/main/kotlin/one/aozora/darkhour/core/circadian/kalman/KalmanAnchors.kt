package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/** Kalman's independently owned daily sleep-midpoint observations. */
internal data class KalmanAnchor(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
    val record: SleepRecord,
)

internal fun prepareKalmanAnchors(
    records: List<SleepRecord>,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
): List<KalmanAnchor> {
    val bestByDate = linkedMapOf<LocalDate, KalmanAnchor>()
    for (record in records) {
        val weight = kalmanAnchorWeight(record) ?: continue
        val candidate = KalmanAnchor(
            dayNumber = ChronoUnit.DAYS.between(globalFirstDate, record.dateOfSleep).toInt(),
            midpointHour = kalmanSleepMidpointHour(record, globalFirstDateMs),
            weight = weight,
            record = record,
        )
        val existing = bestByDate[record.dateOfSleep]
        if (existing == null || candidate.weight > existing.weight) {
            bestByDate[record.dateOfSleep] = candidate
        }
    }
    return bestByDate.values.sortedBy(KalmanAnchor::dayNumber)
}

private fun kalmanAnchorWeight(record: SleepRecord): Double? {
    val quality = record.sleepScore ?: 0.0
    val durationFactor = min(1.0, max(0.0, (record.durationHours - 4.0) / 3.0))
    val weight = quality * durationFactor
    if (weight < 0.05) return null
    return if (record.isMainSleep) weight else weight * 0.15
}

private fun kalmanSleepMidpointHour(record: SleepRecord, globalFirstDateMs: Long): Double {
    val midpointMs = record.startTime.toEpochMilli() + record.durationMs / 2.0
    return (midpointMs - globalFirstDateMs) / 3_600_000.0
}
