package one.aozora.darkhour.core.circadian.adaptive

import one.aozora.darkhour.core.circadian.kalman.KalmanDriftObservation
import one.aozora.darkhour.core.circadian.kalman.KalmanTrendState
import one.aozora.darkhour.core.circadian.kalman.applyTerminalDriftObservation
import one.aozora.darkhour.core.circadian.kalman.resolveKalmanAmbiguity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Produces causal phase and velocity observations at the data edge. Unlike a
 * change detector, the trailing fit is always available when sufficiently
 * supported; residual scatter controls how strongly it can alter the state.
 */
internal fun estimateRecentTrendObservation(
    observations: List<AdaptiveKalmanObservation>,
    config: AdaptiveKalmanConfig,
    notBeforeDay: Int? = null,
    referenceStates: List<KalmanTrendState> = emptyList(),
): AdaptiveKalmanRecentTrendObservation? {
    val endpoint = observations.maxOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: return null
    val firstIncludedDay = endpoint - config.recentSlopeWindowDays + 1
    val effectiveFirstDay = max(firstIncludedDay, notBeforeDay ?: firstIncludedDay)
    val samples = observations.filter { it.dayNumber in effectiveFirstDay..endpoint }
        .sortedBy(AdaptiveKalmanObservation::dayNumber)
    if (samples.size < config.recentSlopeMinimumAnchors) return null

    var previousDay = samples.first().dayNumber
    var previousPhase = samples.first().midpointHour
    val referenceByDay = referenceStates.associateBy(KalmanTrendState::dayNumber)
    val weighted = samples.mapIndexed { index, sample ->
        val phase = referenceByDay[sample.dayNumber]?.let { reference ->
            resolveKalmanAmbiguity(sample.midpointHour, reference.phase)
        } ?: if (index == 0) previousPhase else resolveKalmanAmbiguity(
            sample.midpointHour,
            previousPhase + config.driftPrior * (sample.dayNumber - previousDay),
        )
        previousDay = sample.dayNumber
        previousPhase = phase
        val age = (endpoint - sample.dayNumber).toDouble()
        val recency = exp(-0.5 * age * age /
            (config.recentSlopeRecencySigmaDays * config.recentSlopeRecencySigmaDays))
        RecentSlopeSample(
            day = sample.dayNumber.toDouble(),
            phase = phase,
            weight = sample.weight * recency,
        )
    }
    val fit = weightedLineFit(weighted) ?: return null
    val residualVariance = weighted.sumOf { sample ->
        val residual = sample.phase - fit.intercept - fit.slope * sample.day
        sample.weight * residual * residual
    } / max(1.0, weighted.size - 2.0)
    val slopeVariance = max(
        config.recentSlopeVarianceFloor,
        config.recentSlopeVarianceScale * residualVariance / fit.centeredDayWeight,
    )
    // Endpoint level is more local than velocity. A shorter local-linear fit
    // prevents a well-supported recent clock time from inheriting intercept
    // error from the older part of the velocity window.
    val phaseSamples = weighted.filter {
        it.day >= endpoint - config.recentPhaseWindowDays + 1
    }
    val phaseFit = robustWeightedLineFit(
        phaseSamples,
        minimumScale = sqrt(config.recentPhaseVarianceFloor),
    ) ?: fit
    val phaseResidualVariance = phaseSamples.sumOf { sample ->
        val residual = sample.phase - phaseFit.intercept - phaseFit.slope * sample.day
        sample.weight * residual * residual
    } / max(1.0, phaseSamples.size - 2.0)
    val endpointLeverage = 1.0 / phaseFit.totalWeight +
        (endpoint - phaseFit.meanDay) * (endpoint - phaseFit.meanDay) / phaseFit.centeredDayWeight
    val phaseVariance = max(
        config.recentPhaseVarianceFloor,
        config.recentPhaseVarianceScale * phaseResidualVariance * endpointLeverage,
    )
    return AdaptiveKalmanRecentTrendObservation(
        drift = KalmanDriftObservation(endpoint, fit.slope, slopeVariance),
        endpointPhase = phaseFit.intercept + phaseFit.slope * endpoint,
        phaseVariance = phaseVariance,
        correctionStartDay = max(effectiveFirstDay, endpoint - config.recentPhaseCorrectionDays + 1),
    )
}

internal data class AdaptiveKalmanRecentTrendObservation(
    val drift: KalmanDriftObservation,
    val endpointPhase: Double,
    val phaseVariance: Double,
    val correctionStartDay: Int,
)

/**
 * Assimilates the fit without recreating the last-day phase artifact. Velocity
 * changes only at the endpoint; the correlated intercept error is spread over
 * a bounded trailing lag with zero slope at both ends of the correction.
 */
internal fun applyRecentTrendObservation(
    states: List<KalmanTrendState>,
    observation: AdaptiveKalmanRecentTrendObservation,
    config: AdaptiveKalmanConfig,
): List<KalmanTrendState> {
    val driftAdjusted = applyTerminalDriftObservation(states, observation.drift, config.asKalmanConfig())
    val endpointIndex = driftAdjusted.indexOfFirst { it.dayNumber == observation.drift.dayNumber }
    if (endpointIndex < 0) return driftAdjusted

    val endpoint = driftAdjusted[endpointIndex]
    val target = resolveKalmanAmbiguity(observation.endpointPhase, endpoint.phase)
    val gain = endpoint.phaseVariance / (endpoint.phaseVariance + observation.phaseVariance)
    val correction = gain * (target - endpoint.phase)
    val startIndex = driftAdjusted.indexOfFirst { it.dayNumber >= observation.correctionStartDay }
        .takeIf { it >= 0 } ?: endpointIndex
    val span = (endpoint.dayNumber - driftAdjusted[startIndex].dayNumber).coerceAtLeast(1).toDouble()

    return driftAdjusted.mapIndexed { index, state ->
        val fraction = when {
            index < startIndex -> 0.0
            index >= endpointIndex -> 1.0
            else -> (state.dayNumber - driftAdjusted[startIndex].dayNumber) / span
        }
        val smoothFraction = fraction * fraction * (3.0 - 2.0 * fraction)
        state.copy(phase = state.phase + correction * smoothFraction)
    }
}

private data class RecentSlopeSample(val day: Double, val phase: Double, val weight: Double)
private data class RecentSlopeFit(
    val intercept: Double,
    val slope: Double,
    val centeredDayWeight: Double,
    val totalWeight: Double,
    val meanDay: Double,
)

private fun weightedLineFit(samples: List<RecentSlopeSample>): RecentSlopeFit? {
    val totalWeight = samples.sumOf(RecentSlopeSample::weight)
    if (totalWeight <= 1e-12) return null
    val meanDay = samples.sumOf { it.weight * it.day } / totalWeight
    val meanPhase = samples.sumOf { it.weight * it.phase } / totalWeight
    val centeredDayWeight = samples.sumOf { it.weight * (it.day - meanDay) * (it.day - meanDay) }
    if (centeredDayWeight <= 1e-12) return null
    val covariance = samples.sumOf { it.weight * (it.day - meanDay) * (it.phase - meanPhase) }
    val slope = covariance / centeredDayWeight
    return RecentSlopeFit(
        intercept = meanPhase - slope * meanDay,
        slope = slope,
        centeredDayWeight = centeredDayWeight,
        totalWeight = totalWeight,
        meanDay = meanDay,
    )
}

private fun robustWeightedLineFit(
    samples: List<RecentSlopeSample>,
    minimumScale: Double,
): RecentSlopeFit? {
    var fit = weightedLineFit(samples) ?: return null
    repeat(4) {
        val residuals = samples.map { it.phase - fit.intercept - fit.slope * it.day }
        val center = residuals.median()
        val scale = max(minimumScale, 1.4826 * residuals.map { abs(it - center) }.median())
        val cutoff = 1.5 * scale
        val reweighted = samples.mapIndexed { index, sample ->
            val centeredResidual = abs(residuals[index] - center)
            sample.copy(weight = sample.weight * min(1.0, cutoff / max(centeredResidual, 1e-12)))
        }
        fit = weightedLineFit(reweighted) ?: return fit
    }
    return fit
}

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
