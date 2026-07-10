package one.aozora.darkhour.core.circadian

import kotlin.math.ceil
import kotlin.math.exp

/** One algorithm-selected sleep-duration observation, expressed in calendar days. */
data class DurationObservation(
    val dayNumber: Int,
    val durationHours: Double,
)

/** Shared duration-window model for circadian estimators. */
data class DurationSmoothingConfig(
    val sigmaDays: Double = DEFAULT_DURATION_SMOOTHING_SIGMA_DAYS,
) {
    init {
        require(sigmaDays > 0.0 && sigmaDays.isFinite())
    }
}

const val DEFAULT_DURATION_SMOOTHING_SIGMA_DAYS = 30.0;

/**
 * Gaussian-smooths duration observations by calendar-day distance. A target
 * outside the three-sigma support falls back to the segment mean, keeping
 * long gaps and distant forecasts stable.
 */
fun smoothDurations(
    observations: List<DurationObservation>,
    targetDays: IntRange,
    config: DurationSmoothingConfig = DurationSmoothingConfig(),
): List<Double> {
    if (targetDays.isEmpty()) return emptyList()
    val usable = observations.filter { it.durationHours.isFinite() && it.durationHours > 0.0 }
    if (usable.isEmpty()) return targetDays.map { DEFAULT_SLEEP_DURATION_HOURS }

    val baseline = usable.map(DurationObservation::durationHours).average()
    val byDay = usable.groupBy(DurationObservation::dayNumber)
    val radius = ceil(config.sigmaDays * 3.0).toInt()
    return targetDays.map { targetDay ->
        var weightedDuration = 0.0
        var totalWeight = 0.0
        for (day in targetDay - radius..targetDay + radius) {
            val distance = day - targetDay
            val weight = exp(-0.5 * (distance / config.sigmaDays) * (distance / config.sigmaDays))
            byDay[day].orEmpty().forEach { observation ->
                weightedDuration += weight * observation.durationHours
                totalWeight += weight
            }
        }
        if (totalWeight > 0.0) weightedDuration / totalWeight else baseline
    }
}

private const val DEFAULT_SLEEP_DURATION_HOURS = 8.0
