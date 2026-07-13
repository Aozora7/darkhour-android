package one.aozora.darkhour.core.circadian.kalman

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

/** A direct, independently uncertain observation of daily phase velocity. */
internal data class KalmanDriftObservation(
    val dayNumber: Int,
    val drift: Double,
    val variance: Double,
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
)

data class KalmanTrendState(
    val dayNumber: Int,
    val phase: Double,
    val drift: Double,
    val phaseVariance: Double,
    val phaseDriftCovariance: Double,
    val driftVariance: Double,
)

internal data class Covariance(val phase: Double, val phaseDrift: Double, val drift: Double)
internal data class FilterState(val phase: Double, val drift: Double, val covariance: Covariance)
internal data class KalmanStateReset(
    val drift: Double,
    val phaseVariance: Double,
    val driftVariance: Double,
    val phase: Double? = null,
    val blend: Double = 1.0,
)
internal data class FilterStep(
    val dayNumber: Int,
    val predicted: FilterState,
    val filtered: FilterState,
    val resolvedObservation: Double?,
    val innovation: Double?,
    val observationWeight: Double?,
    val predictedNext: FilterState? = null,
)

data class KalmanInitialState(val phase: Double, val drift: Double)

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
    initialState: KalmanInitialState? = null,
): List<KalmanTrendState> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()

    return fitUnwrappedKalmanTrendWithResets(observations, firstDay, lastDay, config, initialState)
}

internal fun fitUnwrappedKalmanTrendWithResets(
    observations: List<KalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: KalmanConfig,
    initialState: KalmanInitialState? = null,
    resets: Map<Int, KalmanStateReset> = emptyMap(),
    terminalDriftObservation: KalmanDriftObservation? = null,
): List<KalmanTrendState> {
    val smoothed = rtsSmooth(runKalmanForward(observations, firstDay, lastDay, config, initialState, resets))
    return terminalDriftObservation?.let { applyTerminalDriftObservation(smoothed, it, config) } ?: smoothed
}

internal fun runKalmanForward(
    observations: List<KalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: KalmanConfig,
    initialState: KalmanInitialState? = null,
    resets: Map<Int, KalmanStateReset> = emptyMap(),
): List<FilterStep> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()

    val byDay = observations.associateBy(KalmanObservation::dayNumber)
    val firstObservation = byDay[firstDay] ?: observations.minBy(KalmanObservation::dayNumber)
    var state = FilterState(
        phase = initialState?.phase
            ?: firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        drift = initialState?.drift ?: config.driftPrior,
        covariance = Covariance(config.initialPhaseVariance, 0.0, config.initialDriftVariance),
    )
    val steps = ArrayList<FilterStep>(lastDay - firstDay + 1)

    for (day in firstDay..lastDay) {
        if (day != firstDay) state = predictKalmanState(state, config)
        resets[day]?.let { state = resetKalmanState(state, it, config) }
        val predicted = state
        val observation = byDay[day]
        val resolved = observation?.let { resolveKalmanAmbiguity(it.midpointHour, predicted.phase) }
        val innovation = resolved?.minus(predicted.phase)
        observation?.let { state = updateKalmanState(predicted, it, config, checkNotNull(resolved)) }
        steps += FilterStep(day, predicted, state, resolved, innovation, observation?.weight)
    }
    for (index in 0 until steps.lastIndex) {
        val nextDay = steps[index + 1].dayNumber
        var predictedNext = predictKalmanState(steps[index].filtered, config)
        resets[nextDay]?.let { predictedNext = resetKalmanState(predictedNext, it, config) }
        steps[index] = steps[index].copy(predictedNext = predictedNext)
    }
    return steps
}

internal fun applyTerminalDriftObservation(
    states: List<KalmanTrendState>,
    observation: KalmanDriftObservation,
    config: KalmanConfig,
): List<KalmanTrendState> {
    val endpointIndex = states.indexOfFirst { it.dayNumber == observation.dayNumber }
    if (endpointIndex < 0) return states
    val endpoint = states[endpointIndex]
    var projected = updateKalmanDrift(
        FilterState(
            phase = endpoint.phase,
            drift = endpoint.drift,
            covariance = Covariance(
                endpoint.phaseVariance,
                endpoint.phaseDriftCovariance,
                endpoint.driftVariance,
            ),
        ),
        observation,
    )
    val result = states.toMutableList()
    for (index in endpointIndex..states.lastIndex) {
        if (index > endpointIndex) projected = predictKalmanState(projected, config)
        result[index] = KalmanTrendState(
            dayNumber = states[index].dayNumber,
            phase = projected.phase,
            drift = projected.drift,
            phaseVariance = projected.covariance.phase,
            phaseDriftCovariance = projected.covariance.phaseDrift,
            driftVariance = projected.covariance.drift,
        )
    }
    return result
}

internal fun updateKalmanDrift(
    state: FilterState,
    observation: KalmanDriftObservation,
): FilterState {
    require(observation.variance > 0.0)
    val p = state.covariance
    val innovationVariance = p.drift + observation.variance
    val driftGain = p.drift / innovationVariance
    val innovation = observation.drift - state.drift
    return FilterState(
        // The slope observation is derived from the same phase anchors already
        // assimilated above. Re-applying its phase cross-covariance would count
        // those anchors twice and create a visible jump at the data boundary.
        phase = state.phase,
        drift = state.drift + driftGain * innovation,
        covariance = Covariance(
            phase = p.phase,
            phaseDrift = (1.0 - driftGain) * p.phaseDrift,
            drift = max(1e-9, (1.0 - driftGain) * p.drift),
        ),
    )
}

internal fun predictKalmanState(state: FilterState, config: KalmanConfig): FilterState {
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

internal fun updateKalmanState(
    state: FilterState,
    observation: KalmanObservation,
    config: KalmanConfig,
    measuredPhase: Double = resolveKalmanAmbiguity(observation.midpointHour, state.phase),
): FilterState {
    val p = state.covariance
    val measurementVariance = config.measurementVarianceAtUnitWeight / max(observation.weight, 1e-6)
    val innovationVariance = p.phase + measurementVariance
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

internal fun resolveKalmanAmbiguity(measurement: Double, predictedPhase: Double): Double =
    measurement + round((predictedPhase - measurement) / 24.0) * 24.0

internal fun resetKalmanState(
    state: FilterState,
    reset: KalmanStateReset,
    config: KalmanConfig,
): FilterState = state.copy(
    phase = reset.phase?.let { state.phase + reset.blend * (it - state.phase) } ?: state.phase,
    drift = state.drift + reset.blend * (reset.drift - state.drift),
    covariance = Covariance(
        phase = max(state.covariance.phase, reset.phaseVariance),
        phaseDrift = 0.0,
        drift = max(config.processDriftVariance, reset.driftVariance),
    ),
)

private fun rtsSmooth(steps: List<FilterStep>): List<KalmanTrendState> {
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
            dayNumber = steps[index].dayNumber,
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
