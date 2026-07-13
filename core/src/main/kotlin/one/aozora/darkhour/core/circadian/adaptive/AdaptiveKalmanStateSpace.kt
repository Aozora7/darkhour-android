package one.aozora.darkhour.core.circadian.adaptive

import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
import one.aozora.darkhour.core.circadian.kalman.KalmanObservation
import one.aozora.darkhour.core.circadian.kalman.KalmanStateReset
import one.aozora.darkhour.core.circadian.kalman.fitUnwrappedKalmanTrendWithResets

internal fun AdaptiveKalmanConfig.asKalmanConfig() = KalmanConfig(
    driftPrior = driftPrior,
    initialPhaseVariance = initialPhaseVariance,
    initialDriftVariance = initialDriftVariance,
    processPhaseVariance = processPhaseVariance,
    processDriftVariance = processDriftVariance,
    measurementVarianceAtUnitWeight = measurementVarianceAtUnitWeight,
    gateStandardDeviations = gateStandardDeviations,
)

internal fun runAdaptiveSmoother(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: AdaptiveKalmanConfig,
    transitionConfig: AdaptiveKalmanTransitionConfig?,
    transitions: List<AdaptiveKalmanTransition>,
): List<AdaptiveKalmanState> {
    val evidenceByDay = transitions.associate { it.boundaryDay to it.evidence }
    val committedTransitions = if (transitionConfig == null) {
        emptyList()
    } else {
        transitions.filter(AdaptiveKalmanTransition::committed)
    }
    val resetConfig = transitionConfig
    val states = fitUnwrappedKalmanTrendWithResets(
        observations = observations.map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) },
        firstDay = firstDay,
        lastDay = lastDay,
        config = config.asKalmanConfig(),
        resets = committedTransitions.associate { transition ->
            val transitionSettings = checkNotNull(resetConfig)
            transition.boundaryDay to KalmanStateReset(
                drift = transition.newDrift,
                phaseVariance = transition.evidence * transitionSettings.transitionPhaseVariance,
                driftVariance = transitionSettings.transitionDriftVariance,
                phase = transition.boundaryPhase,
                blend = transition.evidence,
            )
        },
    )
    return states.map { state ->
        AdaptiveKalmanState(
            dayNumber = state.dayNumber,
            phase = state.phase,
            drift = state.drift,
            phaseVariance = state.phaseVariance,
            transitionEvidence = evidenceByDay[state.dayNumber] ?: 0.0,
        )
    }
}
