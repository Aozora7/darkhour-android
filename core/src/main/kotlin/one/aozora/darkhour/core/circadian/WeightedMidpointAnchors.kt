package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

internal data class WeightedMidpointAnchor(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
    val record: SleepRecord,
)

internal fun prepareWeightedMidpointAnchors(
    records: List<SleepRecord>,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
): List<WeightedMidpointAnchor> {
    val bestByDate = linkedMapOf<LocalDate, WeightedMidpointAnchor>()
    for (record in records) {
        val weight = weightedMidpointAnchorWeight(record) ?: continue
        val midpointMs = record.startTime.toEpochMilli() + record.durationMs / 2.0
        val candidate = WeightedMidpointAnchor(
            dayNumber = ChronoUnit.DAYS.between(globalFirstDate, record.dateOfSleep).toInt(),
            midpointHour = (midpointMs - globalFirstDateMs) / 3_600_000.0,
            weight = weight,
            record = record,
        )
        val existing = bestByDate[record.dateOfSleep]
        if (existing == null || candidate.weight > existing.weight) {
            bestByDate[record.dateOfSleep] = candidate
        }
    }
    return bestByDate.values.sortedBy(WeightedMidpointAnchor::dayNumber)
}

private fun weightedMidpointAnchorWeight(record: SleepRecord): Double? {
    val quality = record.sleepScore ?: 0.0
    val durationFactor = min(1.0, max(0.0, (record.durationHours - 4.0) / 3.0))
    val weight = quality * durationFactor
    if (weight < 0.05) return null
    return if (record.isMainSleep) weight else weight * 0.15
}
