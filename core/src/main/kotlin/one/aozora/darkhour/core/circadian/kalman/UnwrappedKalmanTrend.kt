package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.DurationSmoothingConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/** One weighted, unwrapped sleep-midpoint observation. */
data class KalmanObservation(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
)

/**
 * A compact linear-Gaussian model of unwrapped sleep timing.  Phase is in
 * hours; drift is the daily phase change in hours per calendar day.
 */
data class KalmanConfig(
    val driftPrior: Double = 1.0,
    val initialPhaseVariance: Double = 4.0,
    val initialDriftVariance: Double = 1.0,
    val processPhaseVariance: Double = 0.08,
    val processDriftVariance: Double = 0.001,
    val measurementVarianceAtUnitWeight: Double = 4.0,
    val gateStandardDeviations: Double = 6.0,
    val durationSmoothing: DurationSmoothingConfig = DurationSmoothingConfig(),
)

data class KalmanTrendState(
    val dayNumber: Int,
    val phase: Double,
    val drift: Double,
    val phaseVariance: Double,
    val phaseDriftCovariance: Double,
    val driftVariance: Double,
)

private data class Covariance(val phase: Double, val phaseDrift: Double, val drift: Double)
private data class FilterState(val phase: Double, val drift: Double, val covariance: Covariance)
private data class FilterStep(val filtered: FilterState, val predictedNext: FilterState?)

/**
 * Fits a continuous trend to one daily observation.  Each clock-time
 * measurement is put on the 24-hour branch nearest the prediction before a
 * standard scalar Kalman update; the result is then RTS-smoothed.
 */
fun fitUnwrappedKalmanTrend(
    observations: List<KalmanObservation>,
    firstDay: Int = observations.minOfOrNull(KalmanObservation::dayNumber) ?: 0,
    lastDay: Int = observations.maxOfOrNull(KalmanObservation::dayNumber) ?: firstDay,
    config: KalmanConfig = KalmanConfig(),
): List<KalmanTrendState> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()

    val byDay = observations.associateBy(KalmanObservation::dayNumber)
    val firstObservation = byDay[firstDay] ?: observations.minBy(KalmanObservation::dayNumber)
    var state = FilterState(
        phase = firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        drift = config.driftPrior,
        covariance = Covariance(config.initialPhaseVariance, 0.0, config.initialDriftVariance),
    )
    val steps = ArrayList<FilterStep>(lastDay - firstDay + 1)

    for (day in firstDay..lastDay) {
        if (day != firstDay) state = predict(state, config)
        byDay[day]?.let { state = update(state, it, config) }
        steps += FilterStep(state, predictedNext = null)
    }
    for (index in 0 until steps.lastIndex) {
        steps[index] = steps[index].copy(predictedNext = predict(steps[index].filtered, config))
    }
    return rtsSmooth(steps, firstDay)
}

private fun predict(state: FilterState, config: KalmanConfig): FilterState {
    val p = state.covariance
    return FilterState(
        phase = state.phase + state.drift,
        drift = state.drift,
        covariance = Covariance(
            phase = p.phase + 2.0 * p.phaseDrift + p.drift + config.processPhaseVariance,
            phaseDrift = p.phaseDrift + p.drift,
            drift = p.drift + config.processDriftVariance,
        ),
    )
}

private fun update(state: FilterState, observation: KalmanObservation, config: KalmanConfig): FilterState {
    val p = state.covariance
    val measurementVariance = config.measurementVarianceAtUnitWeight / max(observation.weight, 1e-6)
    val innovationVariance = p.phase + measurementVariance
    val measuredPhase = resolveAmbiguity(observation.midpointHour, state.phase)
    val innovation = measuredPhase - state.phase
    if (abs(innovation) > config.gateStandardDeviations * sqrt(innovationVariance)) return state

    val phaseGain = p.phase / innovationVariance
    val driftGain = p.phaseDrift / innovationVariance
    return FilterState(
        phase = state.phase + phaseGain * innovation,
        drift = state.drift + driftGain * innovation,
        covariance = Covariance(
            phase = max(1e-9, (1.0 - phaseGain) * p.phase),
            phaseDrift = (1.0 - phaseGain) * p.phaseDrift,
            drift = max(1e-9, p.drift - driftGain * p.phaseDrift),
        ),
    )
}

private fun resolveAmbiguity(measurement: Double, predictedPhase: Double): Double =
    measurement + round((predictedPhase - measurement) / 24.0) * 24.0

private fun rtsSmooth(steps: List<FilterStep>, firstDay: Int): List<KalmanTrendState> {
    val smoothed = steps.map(FilterStep::filtered).toMutableList()
    for (index in smoothed.lastIndex - 1 downTo 0) {
        val filtered = steps[index].filtered
        val predicted = checkNotNull(steps[index].predictedNext)
        val next = smoothed[index + 1]
        val gain = smootherGain(filtered.covariance, predicted.covariance)
        val phaseInnovation = next.phase - predicted.phase
        val driftInnovation = next.drift - predicted.drift
        smoothed[index] = FilterState(
            phase = filtered.phase + gain[0] * phaseInnovation + gain[1] * driftInnovation,
            drift = filtered.drift + gain[2] * phaseInnovation + gain[3] * driftInnovation,
            covariance = filtered.covariance,
        )
    }
    return smoothed.mapIndexed { index, state ->
        KalmanTrendState(
            dayNumber = firstDay + index,
            phase = state.phase,
            drift = state.drift,
            phaseVariance = state.covariance.phase,
            phaseDriftCovariance = state.covariance.phaseDrift,
            driftVariance = state.covariance.drift,
        )
    }
}

/** P(t|t) F' [P(t+1|t)]^-1 for F = [[1, 1], [0, 1]]. */
private fun smootherGain(filtered: Covariance, predicted: Covariance): DoubleArray {
    val a = filtered.phase + filtered.phaseDrift
    val b = filtered.phaseDrift
    val c = filtered.phaseDrift + filtered.drift
    val d = filtered.drift
    val determinant = predicted.phase * predicted.drift - predicted.phaseDrift * predicted.phaseDrift
    if (determinant <= 1e-12) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    return doubleArrayOf(
        (a * predicted.drift - b * predicted.phaseDrift) / determinant,
        (-a * predicted.phaseDrift + b * predicted.phase) / determinant,
        (c * predicted.drift - d * predicted.phaseDrift) / determinant,
        (-c * predicted.phaseDrift + d * predicted.phase) / determinant,
    )
}
