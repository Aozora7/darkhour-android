package one.aozora.darkhour.core.circadian.csf

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

fun normalizeAngle(angle: Double): Double = ((angle % 24.0) + 24.0) % 24.0

fun circularDiff(a: Double, b: Double): Double {
    val diff = normalizeAngle(a) - normalizeAngle(b)
    return when {
        diff > 12.0 -> diff - 24.0
        diff < -12.0 -> diff + 24.0
        else -> diff
    }
}

fun resolveAmbiguity(measurement: Double, predictedPhase: Double): Double =
    measurement + round((predictedPhase - measurement) / 24.0) * 24.0

/**
 * Circular vector fusion result.  [kappa] is used as a pseudo-precision by
 * production CSF; it is intentionally not exposed as a calibrated posterior
 * concentration parameter.
 */
data class VonMisesResult(val phase: Double, val kappa: Double)

fun vonMisesUpdate(
    priorPhase: Double,
    priorKappa: Double,
    measurement: Double,
    measurementKappa: Double,
): VonMisesResult {
    val scale = 2.0 * PI / 24.0

    val cPrior = priorKappa * cos(priorPhase * scale)
    val sPrior = priorKappa * sin(priorPhase * scale)
    val cMeas = measurementKappa * cos(measurement * scale)
    val sMeas = measurementKappa * sin(measurement * scale)

    val cPost = cPrior + cMeas
    val sPost = sPrior + sMeas

    val r = sqrt(cPost * cPost + sPost * sPost)
    return VonMisesResult(
        phase = atan2(sPost, cPost) / scale,
        kappa = max(r, 0.001),
    )
}

fun initializeState(firstAnchor: CsfAnchor, config: CsfConfig): CsfState =
    CsfState(
        phase = firstAnchor.midpointHour,
        tau = config.tauPrior,
        phaseVar = 1.0,
        tauVar = config.tauPriorVar,
        cov = 0.0,
    )

fun predict(state: CsfState, config: CsfConfig): CsfState {
    val driftPerDay = state.tau - 24.0
    return CsfState(
        phase = state.phase + driftPerDay,
        tau = state.tau,
        phaseVar = max(0.01, state.phaseVar + 2.0 * state.cov + state.tauVar + config.processNoisePhase),
        tauVar = max(0.001, state.tauVar + config.processNoiseTau),
        cov = min(state.cov + state.tauVar, 10.0),
    )
}

fun updatePrior(state: CsfState, config: CsfConfig): CsfState {
    val drift = state.tau - 24.0
    val priorDrift = config.tauPrior - 24.0
    val r = when {
        drift < 0.0 -> config.tauPriorNoise.forward
        drift > priorDrift -> config.tauPriorNoise.backward
        else -> config.tauPriorNoise.none
    }

    val k = state.tauVar / (state.tauVar + r)
    val updatedTau = state.tau + k * (config.tauPrior - state.tau)

    return state.copy(
        tau = updatedTau.coerceIn(TAU_MIN, TAU_MAX),
        tauVar = max(0.001, (1.0 - k) * state.tauVar),
        cov = (1.0 - k) * state.cov,
    )
}

fun update(predicted: CsfState, anchor: CsfAnchor, config: CsfConfig): CsfState {
    val measurementKappa = max(0.001, config.measurementKappaBase * anchor.weight)
    val priorKappa = max(0.001, 1.0 / max(predicted.phaseVar, 0.01))
    val resolvedMeasurement = resolveAmbiguity(anchor.midpointHour, predicted.phase)

    val phaseResidual = circularDiff(resolvedMeasurement, predicted.phase)
    val innovationVar = max(0.01, predicted.phaseVar + 1.0 / measurementKappa)
    val mahalanobisSq = (phaseResidual * phaseResidual) / innovationVar

    if (mahalanobisSq > config.gateThreshold * config.gateThreshold) {
        return predicted
    }

    val normalizedPredicted = normalizeAngle(predicted.phase)
    val normalizedMeasurement = normalizeAngle(resolvedMeasurement)
    val updatedCircular = vonMisesUpdate(
        priorPhase = normalizedPredicted,
        priorKappa = priorKappa,
        measurement = normalizedMeasurement,
        measurementKappa = measurementKappa,
    )

    var phaseCorrection = circularDiff(updatedCircular.phase, normalizedPredicted)
    phaseCorrection = phaseCorrection.coerceIn(-config.maxCorrectionPerStep, config.maxCorrectionPerStep)

    val clampedInnovation = phaseResidual.coerceIn(-config.maxCorrectionPerStep, config.maxCorrectionPerStep)
    val kalmanGain = predicted.cov / innovationVar
    var updatedTau = predicted.tau + kalmanGain * clampedInnovation
    if (!updatedTau.isFinite()) {
        updatedTau = predicted.tau
    }

    return CsfState(
        phase = predicted.phase + phaseCorrection,
        tau = updatedTau.coerceIn(TAU_MIN, TAU_MAX),
        phaseVar = max(0.01, 1.0 / max(updatedCircular.kappa, 0.001)),
        tauVar = max(0.001, predicted.tauVar - kalmanGain * predicted.cov),
        cov = min(predicted.cov - kalmanGain * innovationVar, 1.0),
    )
}

fun forwardPass(anchors: List<CsfAnchor>, firstDay: Int, lastDay: Int, config: CsfConfig): List<CsfState> {
    val anchorByDay = anchors.associateBy { it.dayNumber }
    var state = initializeState(anchors.first(), config)
    val states = mutableListOf(state)

    for (t in (firstDay + 1)..lastDay) {
        state = predict(state, config)
        anchorByDay[t]?.let { anchor ->
            state = update(state, anchor, config)
        }
        state = updatePrior(state, config)
        states += state
    }

    return states
}

/**
 * Backward smoothing for the production heuristic.  Its name reflects the
 * direction of the pass, not a claim that the preceding covariance updates are
 * an exact Rauch--Tung--Striebel implementation.
 */
fun rtsSmoother(forwardStates: List<CsfState>, config: CsfConfig): List<SmoothedState> {
    if (forwardStates.isEmpty()) return emptyList()

    val smoothed = forwardStates.map { SmoothedState(it) }.toMutableList()

    for (t in smoothed.size - 2 downTo 0) {
        val curr = smoothed[t]
        val next = smoothed[t + 1]
        val predictedNextVar = max(0.01, curr.phaseVar + 2.0 * curr.cov + curr.tauVar + config.processNoisePhase)
        val gain = min(0.95, max(0.1, curr.phaseVar / predictedNextVar))

        val expectedPhase = curr.phase + (curr.tau - 24.0)
        val phaseInnov = circularDiff(next.smoothedPhase, expectedPhase)
        val tauInnov = next.smoothedTau - curr.tau

        var smoothedTau = curr.tau + gain * tauInnov
        if (!smoothedTau.isFinite()) {
            smoothedTau = curr.tau
        }

        smoothed[t] = curr.withSmoothed(
            smoothedPhase = curr.phase + gain * phaseInnov,
            smoothedTau = smoothedTau.coerceIn(TAU_MIN, TAU_MAX),
            smoothedPhaseVar = max(0.01, curr.phaseVar * (1.0 - gain)),
            smoothedTauVar = max(0.001, curr.tauVar * (1.0 - gain)),
        )
    }

    return smoothed
}
