package one.aozora.darkhour.core.circadian.adaptive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import one.aozora.darkhour.core.circadian.kalman.Covariance
import one.aozora.darkhour.core.circadian.kalman.FilterState
import one.aozora.darkhour.core.circadian.kalman.KalmanObservation
import one.aozora.darkhour.core.circadian.kalman.KalmanStateReset
import one.aozora.darkhour.core.circadian.kalman.predictKalmanState
import one.aozora.darkhour.core.circadian.kalman.resetKalmanState
import one.aozora.darkhour.core.circadian.kalman.resolveKalmanAmbiguity
import one.aozora.darkhour.core.circadian.kalman.updateKalmanState

private data class ResolvedObservation(
    val dayNumber: Int,
    val phase: Double,
    val weight: Double,
    val predictedDrift: Double,
)
private data class LineFit(val intercept: Double, val slope: Double)
private data class TransitionCandidate(
    val evidence: Double,
    val previousDrift: Double,
    val drift: Double,
    val boundaryPhase: Double,
    val boundaryDay: Int,
    val committed: Boolean,
)

fun detectAdaptiveKalmanTransitions(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int = observations.minOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: 0,
    lastDay: Int = observations.maxOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: firstDay,
    config: AdaptiveKalmanConfig = AdaptiveKalmanConfig(),
    transitionConfig: AdaptiveKalmanTransitionConfig = AdaptiveKalmanTransitionConfig(),
): List<AdaptiveKalmanTransition> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()
    val sorted = observations.sortedBy(AdaptiveKalmanObservation::dayNumber)
    val byDay = sorted.associateBy(AdaptiveKalmanObservation::dayNumber)
    val kalmanConfig = config.asKalmanConfig()
    val firstObservation = sorted.first()
    var state = FilterState(
        phase = firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        drift = config.driftPrior,
        covariance = Covariance(config.initialPhaseVariance, 0.0, config.initialDriftVariance),
    )
    val history = mutableListOf<ResolvedObservation>()
    val transitions = mutableListOf<AdaptiveKalmanTransition>()
    for (day in firstDay..lastDay) {
        var predicted = if (day == firstDay) state else predictKalmanState(state, kalmanConfig)
        val observation = byDay[day]
        if (observation != null) {
            val resolvedPhase = resolveKalmanAmbiguity(observation.midpointHour, predicted.phase)
            val resolved = ResolvedObservation(day, resolvedPhase, observation.weight, predicted.drift)
            var resolvedForHistory = resolved
            val initialRegimeIsLearned = day - firstDay >= 2 * transitionConfig.evidenceWindowDays
            val candidate = if (initialRegimeIsLearned) {
                transitionCandidate(history + resolved, config, transitionConfig)
            } else {
                null
            }
            val regimeIsPersistent = transitions.lastOrNull()
                ?.let { day - it.boundaryDay >= transitionConfig.minimumRegimeDays }
                ?: true
            if (candidate != null && regimeIsPersistent && day != firstDay) {
                val transition = AdaptiveKalmanTransition(
                    boundaryDay = candidate.boundaryDay,
                    confirmationDay = day,
                    previousDrift = candidate.previousDrift,
                    newDrift = candidate.drift,
                    boundaryPhase = candidate.boundaryPhase,
                    evidence = candidate.evidence,
                    committed = candidate.committed,
                )
                AdaptiveKalmanDiagnostics.log(
                    "boundary=${transition.boundaryDay} confirmation=${transition.confirmationDay} " +
                        "lag=${transition.confirmationLagDays} evidence=${transition.evidence} " +
                        "oldDrift=${transition.previousDrift} newDrift=${transition.newDrift}",
                )
                transitions += transition
                // The detector advances its regime hypothesis for every
                // accepted event. Only strict committed events are injected
                // into the separately run published smoother.
                predicted = resetKalmanState(
                    predicted,
                    KalmanStateReset(
                        drift = transition.newDrift,
                        phaseVariance = transition.evidence * transitionConfig.transitionPhaseVariance,
                        driftVariance = transitionConfig.transitionDriftVariance,
                        phase = transition.boundaryPhase,
                        blend = transition.evidence,
                    ),
                    kalmanConfig,
                )
                // Evidence used to confirm one regime must not be reused to
                // propose the reverse transition after the cooldown expires.
                history.clear()
                resolvedForHistory = resolved.copy(predictedDrift = predicted.drift)
            }
            state = updateKalmanState(
                predicted,
                KalmanObservation(observation.dayNumber, observation.midpointHour, observation.weight),
                kalmanConfig,
                resolvedPhase,
            )
            history += resolvedForHistory
            history.removeAll { it.dayNumber < day - 2 * transitionConfig.evidenceWindowDays }
        } else {
            state = predicted
        }
    }
    return transitions.distinctBy(AdaptiveKalmanTransition::boundaryDay)
}

private fun transitionCandidate(
    history: List<ResolvedObservation>,
    config: AdaptiveKalmanConfig,
    transitionConfig: AdaptiveKalmanTransitionConfig,
): TransitionCandidate? {
    // Treat commit settings as an additional gate even if independently
    // tuned registry values cross. Detection must never become stricter than
    // committing the resulting reset.
    val commitMinDriftDelta = max(
        transitionConfig.commitMinDriftDelta,
        transitionConfig.evidenceMinDriftDelta,
    )
    val commitFitImprovement = max(
        transitionConfig.commitFitImprovement,
        transitionConfig.evidenceFitImprovement,
    )
    val commitMaxMeanHuberLoss = min(
        transitionConfig.commitMaxMeanHuberLoss,
        transitionConfig.evidenceMaxMeanHuberLoss,
    )
    val commitMaxHalfSlopeDifference = min(
        transitionConfig.commitMaxHalfSlopeDifference,
        transitionConfig.evidenceMaxHalfSlopeDifference,
    )
    val eligible = history.filter { it.weight >= transitionConfig.evidenceMinAnchorWeight }
    if (eligible.size < transitionConfig.evidenceMinAnchors) return null
    var strongest: TransitionCandidate? = null
    for (start in 0..eligible.size - transitionConfig.evidenceMinAnchors) {
        val suffix = eligible.subList(start, eligible.size)
        if (suffix.last().dayNumber - suffix.first().dayNumber > transitionConfig.evidenceWindowDays) continue
        if (suffix.zipWithNext().any { (previous, next) ->
                next.dayNumber - previous.dayNumber > transitionConfig.evidenceMaxAnchorGapDays
            }
        ) continue
        val previousDrift = eligible
            .lastOrNull { it.dayNumber <= suffix.first().dayNumber - transitionConfig.evidenceMinAnchors }
            ?.predictedDrift
            ?: suffix.first().predictedDrift
        val fit = fitHuberLine(suffix, config.measurementVarianceAtUnitWeight)
        val delta = abs(fit.slope - previousDrift)
        if (delta < transitionConfig.evidenceMinDriftDelta || !isEntrainmentBoundary(previousDrift, fit.slope)) continue
        val oldFit = fitHuberFixedSlope(suffix, previousDrift, config.measurementVarianceAtUnitWeight)
        val oldLoss = robustLoss(suffix, oldFit, config.measurementVarianceAtUnitWeight)
        val newLoss = robustLoss(suffix, fit, config.measurementVarianceAtUnitWeight)
        val ratio = oldLoss / max(newLoss, 1e-9)
        if (
            ratio < transitionConfig.evidenceFitImprovement ||
            newLoss / suffix.size > transitionConfig.evidenceMaxMeanHuberLoss
        ) continue
        val halves = suffix.chunked((suffix.size + 1) / 2)
        if (halves.size < 2 || halves.any { it.size < 3 }) continue
        val halfSlopes = halves.map { fitHuberLine(it, config.measurementVarianceAtUnitWeight).slope }
        if (halfSlopes.any { abs(it - fit.slope) > transitionConfig.evidenceMaxHalfSlopeDifference }) continue
        val halfRatios = halves.map { half ->
            val halfOld = fitHuberFixedSlope(half, previousDrift, config.measurementVarianceAtUnitWeight)
            val halfNew = fitHuberFixedSlope(half, fit.slope, config.measurementVarianceAtUnitWeight)
            robustLoss(half, halfOld, config.measurementVarianceAtUnitWeight) /
                max(robustLoss(half, halfNew, config.measurementVarianceAtUnitWeight), 1e-9)
        }
        if (halfRatios.any { it < transitionConfig.evidenceFitImprovement }) continue
        val weightEvidence = (suffix.sumOf(ResolvedObservation::weight) / transitionConfig.evidenceMinAnchors)
            .coerceIn(0.0, 1.0)
        val ratioEvidence = ((ratio / transitionConfig.evidenceFitImprovement) - 1.0).coerceIn(0.0, 1.0)
        val deltaEvidence = ((delta / transitionConfig.evidenceMinDriftDelta) - 1.0).coerceIn(0.0, 1.0)
        val candidate = TransitionCandidate(
            evidence = weightEvidence * (0.5 + 0.25 * ratioEvidence + 0.25 * deltaEvidence),
            previousDrift = previousDrift,
            drift = fit.slope,
            boundaryPhase = fit.intercept + fit.slope * (suffix.first().dayNumber + 1),
            // The fitted slope governs the interval after the first suffix
            // midpoint, so the state reset belongs to the following day.
            boundaryDay = suffix.first().dayNumber + 1,
            committed = delta >= commitMinDriftDelta &&
                ratio >= commitFitImprovement &&
                newLoss / suffix.size <= commitMaxMeanHuberLoss &&
                halfSlopes.all { abs(it - fit.slope) <= commitMaxHalfSlopeDifference } &&
                halfRatios.all { it >= commitFitImprovement },
        )
        if (candidate.evidence > (strongest?.evidence ?: 0.0)) strongest = candidate
    }
    return strongest
}

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

private const val HUBER_K = 1.345
private const val ROBUST_FIT_ITERATIONS = 12
private const val ENTRAINED_DRIFT_BAND = 0.25
private const val MIN_FREE_RUNNING_DRIFT = 0.45
