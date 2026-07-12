package one.aozora.darkhour.core.circadian.adaptive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

data class AdaptiveKalmanObservation(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
)

data class AdaptiveKalmanConfig(
    val driftPrior: Double = 0.51,
    val initialPhaseVariance: Double = 4.0,
    val initialDriftVariance: Double = 1.0,
    val processPhaseVariance: Double = 0.49,
    val processDriftVariance: Double = 0.0001,
    val measurementVarianceAtUnitWeight: Double = 10.0,
    val gateStandardDeviations: Double = 6.0,
    val evidenceWindowDays: Int = 26,
    val evidenceMinAnchors: Int = 7,
    val evidenceMinAnchorWeight: Double = 0.23,
    val evidenceMinDriftDelta: Double = 0.50,
    val evidenceFitImprovement: Double = 2.4,
    val transitionPhaseVariance: Double = 36.0,
    val transitionDriftVariance: Double = 0.01,
) {
    init {
        require(driftPrior.isFinite())
        require(initialPhaseVariance > 0.0)
        require(initialDriftVariance > 0.0)
        require(processPhaseVariance > 0.0)
        require(processDriftVariance > 0.0)
        require(measurementVarianceAtUnitWeight > 0.0)
        require(gateStandardDeviations > 0.0)
        require(evidenceWindowDays >= 7)
        require(evidenceMinAnchors >= 5)
        require(evidenceMinAnchorWeight > 0.0)
        require(evidenceMinDriftDelta > 0.0)
        require(evidenceFitImprovement > 1.0)
        require(transitionPhaseVariance > 0.0)
        require(transitionDriftVariance > 0.0)
    }
}

data class AdaptiveKalmanState(
    val dayNumber: Int,
    val phase: Double,
    val drift: Double,
    val phaseVariance: Double,
    /** Strength of coherent abrupt-transition evidence used at this step. */
    val transitionEvidence: Double,
)

object AdaptiveKalmanDiagnostics {
    @Volatile var logger: ((String) -> Unit)? = null
    internal fun log(message: String) { logger?.invoke(message) }
}

private data class Covariance(val phase: Double, val phaseDrift: Double, val drift: Double)
private data class FilterState(val phase: Double, val drift: Double, val covariance: Covariance)
private data class FilterStep(
    val dayNumber: Int,
    val predicted: FilterState,
    val filtered: FilterState,
    val transitionEvidence: Double,
    var predictedNext: FilterState? = null,
)

private data class ResolvedObservation(
    val dayNumber: Int,
    val phase: Double,
    val weight: Double,
)

private data class TransitionEvent(
    val boundaryDay: Int,
    val confirmationDay: Int,
    val evidence: Double,
    val candidateDrift: Double,
)

/**
 * Kalman phase/drift estimation with evidence-gated heavy-tailed process noise.
 *
 * Ordinary nights use the same low drift process variance as the production
 * unwrapped Kalman model. A much wider process component is mixed in only
 * when a robust suffix fit contains enough mutually consistent observations
 * to support a transition between entrained and free-running drift. Isolated
 * phase shifts and alternating noise therefore stay in the observation model
 * instead of turning into day-to-day tau movement.
 */
fun fitAdaptiveKalmanTrend(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int = observations.minOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: 0,
    lastDay: Int = observations.maxOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: firstDay,
    config: AdaptiveKalmanConfig = AdaptiveKalmanConfig(),
): List<AdaptiveKalmanState> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()
    val sorted = observations.sortedBy(AdaptiveKalmanObservation::dayNumber)
    val byDay = sorted.associateBy(AdaptiveKalmanObservation::dayNumber)
    val firstObservation = sorted.first()
    val transitions = detectTransitions(sorted, firstDay, lastDay, config)
        .associateBy(TransitionEvent::boundaryDay)
    var state = FilterState(
        phase = firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        drift = config.driftPrior,
        covariance = Covariance(config.initialPhaseVariance, 0.0, config.initialDriftVariance),
    )
    val steps = ArrayList<FilterStep>(lastDay - firstDay + 1)

    for (day in firstDay..lastDay) {
        var predicted = if (day == firstDay) state else predict(state, config)
        val transition = transitions[day]
        if (transition != null && day != firstDay) {
            predicted = withAdaptiveDriftTransition(
                predicted,
                TransitionAdaptation(transition.evidence, transition.candidateDrift, transition.boundaryDay),
                config,
            )
        }
        val observation = byDay[day]
        if (observation != null) {
            val resolvedPhase = resolveAmbiguity(observation.midpointHour, predicted.phase)
            state = update(predicted, observation, resolvedPhase, config)
        } else {
            state = predicted
        }
        steps.lastOrNull()?.predictedNext = predicted
        steps += FilterStep(day, predicted, state, transition?.evidence ?: 0.0)
    }
    return rtsSmooth(steps)
}

private fun detectTransitions(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: AdaptiveKalmanConfig,
): List<TransitionEvent> {
    val byDay = observations.associateBy(AdaptiveKalmanObservation::dayNumber)
    val firstObservation = observations.first()
    var state = FilterState(
        phase = firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        drift = config.driftPrior,
        covariance = Covariance(config.initialPhaseVariance, 0.0, config.initialDriftVariance),
    )
    val history = mutableListOf<ResolvedObservation>()
    val events = mutableListOf<TransitionEvent>()
    var lastConfirmationDay: Int? = null
    for (day in firstDay..lastDay) {
        var predicted = if (day == firstDay) state else predict(state, config)
        val observation = byDay[day]
        if (observation != null) {
            val resolvedPhase = resolveAmbiguity(observation.midpointHour, predicted.phase)
            val candidate = ResolvedObservation(day, resolvedPhase, observation.weight)
            val adaptation = transitionAdaptation(history + candidate, predicted.drift, config)
            val outsideCooldown = lastConfirmationDay?.let { day - it >= TRANSITION_COOLDOWN_DAYS } ?: true
            if (adaptation != null && outsideCooldown && day != firstDay) {
                AdaptiveKalmanDiagnostics.log(
                    "boundary=${adaptation.boundaryDay} confirmation=$day evidence=${adaptation.evidence} " +
                        "oldDrift=${predicted.drift} candidateDrift=${adaptation.candidateDrift}",
                )
                events += TransitionEvent(
                    boundaryDay = adaptation.boundaryDay,
                    confirmationDay = day,
                    evidence = adaptation.evidence,
                    candidateDrift = adaptation.candidateDrift,
                )
                predicted = withAdaptiveDriftTransition(predicted, adaptation, config)
                lastConfirmationDay = day
                history.removeAll { it.dayNumber < adaptation.boundaryDay }
            }
            state = update(predicted, observation, resolvedPhase, config)
            history += candidate
            history.removeAll { it.dayNumber < day - config.evidenceWindowDays }
        } else {
            state = predicted
        }
    }
    return events.distinctBy(TransitionEvent::boundaryDay)
}

private fun predict(state: FilterState, config: AdaptiveKalmanConfig): FilterState {
    val covariance = state.covariance
    return FilterState(
        phase = state.phase + state.drift,
        drift = state.drift,
        covariance = Covariance(
            phase = covariance.phase + 2.0 * covariance.phaseDrift + covariance.drift +
                config.processPhaseVariance,
            phaseDrift = covariance.phaseDrift + covariance.drift,
            drift = covariance.drift + config.processDriftVariance,
        ),
    )
}

/** Integrated drift shock: the changed period also affects phase over this interval. */
private fun withAdaptiveDriftTransition(
    state: FilterState,
    adaptation: TransitionAdaptation,
    config: AdaptiveKalmanConfig,
): FilterState = state.copy(
    drift = adaptation.candidateDrift,
    covariance = state.covariance.let { covariance ->
        val phaseVariance = adaptation.evidence * config.transitionPhaseVariance
        Covariance(
            phase = max(covariance.phase, phaseVariance),
            phaseDrift = 0.0,
            drift = max(config.processDriftVariance, config.transitionDriftVariance),
        )
    },
)

private fun update(
    predicted: FilterState,
    observation: AdaptiveKalmanObservation,
    resolvedPhase: Double,
    config: AdaptiveKalmanConfig,
): FilterState {
    val covariance = predicted.covariance
    val measurementVariance = config.measurementVarianceAtUnitWeight / max(observation.weight, 1e-6)
    val innovationVariance = covariance.phase + measurementVariance
    val innovation = resolvedPhase - predicted.phase
    if (abs(innovation) > config.gateStandardDeviations * sqrt(innovationVariance)) return predicted
    val phaseGain = covariance.phase / innovationVariance
    val driftGain = covariance.phaseDrift / innovationVariance
    return FilterState(
        phase = predicted.phase + phaseGain * innovation,
        drift = predicted.drift + driftGain * innovation,
        covariance = Covariance(
            phase = max(1e-9, (1.0 - phaseGain) * covariance.phase),
            phaseDrift = (1.0 - phaseGain) * covariance.phaseDrift,
            drift = max(1e-9, covariance.drift - driftGain * covariance.phaseDrift),
        ),
    )
}

private data class TransitionAdaptation(
    val evidence: Double,
    val candidateDrift: Double,
    val boundaryDay: Int,
)

private fun transitionAdaptation(
    history: List<ResolvedObservation>,
    currentDrift: Double,
    config: AdaptiveKalmanConfig,
): TransitionAdaptation? {
    val eligible = history.filter { it.weight >= config.evidenceMinAnchorWeight }
    if (eligible.size < config.evidenceMinAnchors) return null
    var strongest: TransitionAdaptation? = null
    for (start in 0..eligible.size - config.evidenceMinAnchors) {
        val suffix = eligible.subList(start, eligible.size)
        if (suffix.last().dayNumber - suffix.first().dayNumber > config.evidenceWindowDays) continue
        val fit = fitHuberLine(suffix, config.measurementVarianceAtUnitWeight)
        val delta = abs(fit.slope - currentDrift)
        if (delta < config.evidenceMinDriftDelta || !isEntrainmentBoundary(currentDrift, fit.slope)) continue
        val oldFit = fitHuberFixedSlope(suffix, currentDrift, config.measurementVarianceAtUnitWeight)
        val oldLoss = robustLoss(suffix, oldFit, config.measurementVarianceAtUnitWeight)
        val newLoss = robustLoss(suffix, fit, config.measurementVarianceAtUnitWeight)
        val ratio = oldLoss / max(newLoss, 1e-9)
        if (ratio < config.evidenceFitImprovement) continue
        if (newLoss / suffix.size > MAX_MEAN_HUBER_LOSS) continue
        val halves = suffix.chunked((suffix.size + 1) / 2)
        if (halves.size < 2 || halves.any { it.size < 3 }) continue
        val halfSlopes = halves.map { fitHuberLine(it, config.measurementVarianceAtUnitWeight).slope }
        if (halfSlopes.any { abs(it - fit.slope) > MAX_HALF_SLOPE_DIFFERENCE }) continue
        val halfRatios = halves.map { half ->
            val halfOld = fitHuberFixedSlope(half, currentDrift, config.measurementVarianceAtUnitWeight)
            val halfNew = fitHuberFixedSlope(half, fit.slope, config.measurementVarianceAtUnitWeight)
            robustLoss(half, halfOld, config.measurementVarianceAtUnitWeight) /
                max(robustLoss(half, halfNew, config.measurementVarianceAtUnitWeight), 1e-9)
        }
        if (halfRatios.any { it < config.evidenceFitImprovement }) continue
        val weightEvidence = (suffix.sumOf(ResolvedObservation::weight) / config.evidenceMinAnchors)
            .coerceIn(0.0, 1.0)
        val ratioEvidence = ((ratio / config.evidenceFitImprovement) - 1.0).coerceIn(0.0, 1.0)
        val deltaEvidence = ((delta / config.evidenceMinDriftDelta) - 1.0).coerceIn(0.0, 1.0)
        val evidence = weightEvidence * (0.5 + 0.25 * ratioEvidence + 0.25 * deltaEvidence)
        if (evidence > (strongest?.evidence ?: 0.0)) {
            strongest = TransitionAdaptation(evidence, fit.slope, suffix.first().dayNumber)
        }
    }
    return strongest
}

private data class LineFit(val intercept: Double, val slope: Double)

private fun fitHuberLine(samples: List<ResolvedObservation>, variance: Double): LineFit {
    var fit = weightedLine(samples) { it.weight / variance }
    repeat(ROBUST_FIT_ITERATIONS) {
        val current = fit
        fit = weightedLine(samples) { sample ->
            val residual = standardizedResidual(sample, current, variance)
            val robustWeight = if (abs(residual) <= HUBER_K) 1.0 else HUBER_K / abs(residual)
            robustWeight * sample.weight / variance
        }
        if (abs(fit.intercept - current.intercept) + abs(fit.slope - current.slope) < 1e-7) return fit
    }
    return fit
}

private fun fitHuberFixedSlope(
    samples: List<ResolvedObservation>,
    slope: Double,
    variance: Double,
): LineFit {
    var intercept = weightedIntercept(samples, slope) { it.weight / variance }
    repeat(ROBUST_FIT_ITERATIONS) {
        val current = intercept
        intercept = weightedIntercept(samples, slope) { sample ->
            val residual = standardizedResidual(sample, LineFit(current, slope), variance)
            val robustWeight = if (abs(residual) <= HUBER_K) 1.0 else HUBER_K / abs(residual)
            robustWeight * sample.weight / variance
        }
        if (abs(intercept - current) < 1e-7) return LineFit(intercept, slope)
    }
    return LineFit(intercept, slope)
}

private inline fun weightedLine(
    samples: List<ResolvedObservation>,
    weight: (ResolvedObservation) -> Double,
): LineFit {
    val origin = samples.first().dayNumber
    var sumWeight = 0.0
    var sumX = 0.0
    var sumY = 0.0
    for (sample in samples) {
        val w = weight(sample)
        sumWeight += w
        sumX += w * (sample.dayNumber - origin)
        sumY += w * sample.phase
    }
    if (sumWeight <= 1e-12) return LineFit(samples.first().phase, 0.0)
    val meanX = sumX / sumWeight
    val meanY = sumY / sumWeight
    var numerator = 0.0
    var denominator = 0.0
    for (sample in samples) {
        val w = weight(sample)
        val x = sample.dayNumber - origin
        numerator += w * (x - meanX) * (sample.phase - meanY)
        denominator += w * (x - meanX) * (x - meanX)
    }
    val slope = if (denominator > 1e-12) numerator / denominator else 0.0
    return LineFit(meanY - slope * meanX - slope * origin, slope)
}

private inline fun weightedIntercept(
    samples: List<ResolvedObservation>,
    slope: Double,
    weight: (ResolvedObservation) -> Double,
): Double {
    var totalWeight = 0.0
    var total = 0.0
    for (sample in samples) {
        val w = weight(sample)
        totalWeight += w
        total += w * (sample.phase - slope * sample.dayNumber)
    }
    return if (totalWeight > 1e-12) total / totalWeight else samples.first().phase
}

private fun robustLoss(samples: List<ResolvedObservation>, fit: LineFit, variance: Double): Double =
    samples.sumOf { sample ->
        val residual = abs(standardizedResidual(sample, fit, variance))
        if (residual <= HUBER_K) 0.5 * residual * residual
        else HUBER_K * (residual - 0.5 * HUBER_K)
    }

private fun standardizedResidual(sample: ResolvedObservation, fit: LineFit, variance: Double): Double =
    (sample.phase - fit.intercept - fit.slope * sample.dayNumber) / sqrt(variance / sample.weight)

private fun isEntrainmentBoundary(previousDrift: Double, candidateDrift: Double): Boolean {
    fun entrained(drift: Double) = abs(drift) <= ENTRAINED_DRIFT_BAND
    fun freeRunning(drift: Double) = abs(drift) >= MIN_FREE_RUNNING_DRIFT
    return (entrained(previousDrift) && freeRunning(candidateDrift)) ||
        (freeRunning(previousDrift) && entrained(candidateDrift))
}

private fun rtsSmooth(steps: List<FilterStep>): List<AdaptiveKalmanState> {
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
        AdaptiveKalmanState(
            dayNumber = steps[index].dayNumber,
            phase = state.phase,
            drift = state.drift,
            phaseVariance = state.covariance.phase,
            transitionEvidence = steps[index].transitionEvidence,
        )
    }
}

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

private fun resolveAmbiguity(measurement: Double, predictedPhase: Double): Double =
    measurement + round((predictedPhase - measurement) / 24.0) * 24.0

private const val HUBER_K = 1.345
private const val ROBUST_FIT_ITERATIONS = 12
private const val MAX_MEAN_HUBER_LOSS = 0.008
private const val MAX_HALF_SLOPE_DIFFERENCE = 0.30
private const val ENTRAINED_DRIFT_BAND = 0.25
private const val MIN_FREE_RUNNING_DRIFT = 0.45
private const val TRANSITION_COOLDOWN_DAYS = 14
