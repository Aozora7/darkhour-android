package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.periodogram.PeriodogramAnchor
import one.aozora.darkhour.core.periodogram.PeriodogramOptions
import one.aozora.darkhour.core.periodogram.computePeriodogram
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

internal data class DetectedSwitchingChangePoint(
    val dayNumber: Int,
    val confirmationDayNumber: Int,
    val posteriorProbability: Double,
    val previousDrift: Double,
    val newDrift: Double,
    val observationOffset: Double,
    val boundaryState: SwitchingState,
)

object SwitchingKalmanDiagnostics {
    @Volatile var logger: ((String) -> Unit)? = null
    internal fun log(message: String) { logger?.invoke(message) }
}

private data class CandidateBoundary(
    val dayNumber: Int,
    val previousDrift: Double,
    val boundaryState: SwitchingState,
    val resetMode: String,
)

private data class SwitchingHypothesis(
    val state: SwitchingState,
    val regimeStartDay: Int,
    val evidence: Double,
    val logProbability: Double,
    val candidate: CandidateBoundary?,
)

internal fun detectSwitchingKalmanChangePoints(
    observations: List<KalmanObservation>,
    config: SwitchingKalmanConfig,
): List<DetectedSwitchingChangePoint> {
    if (observations.size < 2) return emptyList()
    val sorted = observations.sortedBy(KalmanObservation::dayNumber)
    val first = sorted.first()
    var hypotheses = listOf(
        SwitchingHypothesis(
            state = updateSwitchingState(
                initialSwitchingState(first.midpointHour, config),
                first,
                config,
            ).state,
            regimeStartDay = first.dayNumber,
            evidence = first.weight,
            logProbability = 0.0,
            candidate = null,
        ),
    )
    val committed = mutableListOf<DetectedSwitchingChangePoint>()
    var previousDay = first.dayNumber
    var committedRegimeStart = first.dayNumber
    var learnedFreeRunningDrift: Double? = null

    for ((observationIndex, observation) in sorted.drop(1).withIndex()) {
        val gapDays = max(1, observation.dayNumber - previousDay)
        val dailyHazard = 1.0 / config.regimePriorDays
        val hazard = (1.0 - (1.0 - dailyHazard).pow(gapDays.toDouble())).coerceIn(1e-9, 1.0 - 1e-9)
        val branches = ArrayList<SwitchingHypothesis>(hypotheses.size * 2)
        for (hypothesis in hypotheses) {
            val predicted = predictSwitchingState(hypothesis.state, gapDays, config)
            val continued = updateSwitchingState(predicted, observation, config)
            branches += hypothesis.copy(
                state = continued.state,
                evidence = hypothesis.evidence + observation.weight,
                logProbability = hypothesis.logProbability + ln(1.0 - hazard) + continued.logLikelihood,
            )
            // A hypothesis currently stores only its most recent tentative
            // boundary. Do not let it create another boundary and silently
            // discard the first one before that first decision is committed.
            // The no-change history remains in the beam and continues to
            // propose newer candidates on every observation.
            if (hypothesis.candidate == null && hypothesis.evidence >= config.regimeMinEvidence) {
                resetModes(
                    previousDrift = predicted.drift,
                    learnedFreeRunningDrift = learnedFreeRunningDrift ?: config.driftPrior,
                    config = config,
                ).forEach { mode ->
                    val reset = resetSwitchingState(predicted, config, mode.driftMean)
                    val changed = updateSwitchingState(reset, observation, config)
                    branches += SwitchingHypothesis(
                        state = changed.state,
                        regimeStartDay = observation.dayNumber,
                        evidence = observation.weight,
                        logProbability = hypothesis.logProbability + ln(hazard) + ln(mode.weight) +
                            changed.logLikelihood,
                        candidate = CandidateBoundary(
                            dayNumber = observation.dayNumber,
                            previousDrift = hypothesis.state.drift,
                            boundaryState = reset,
                            resetMode = mode.label,
                        ),
                    )
                }
            }
        }
        hypotheses = normalizeAndPrune(branches)
        val candidateHypotheses = hypotheses.filter { it.candidate != null }
        if (candidateHypotheses.isNotEmpty()) {
            val posterior = candidateHypotheses.sumOf(::probability)
            val best = candidateHypotheses.maxBy(SwitchingHypothesis::logProbability)
            val tailDiagnostic = observationIndex >= sorted.size - TAIL_DIAGNOSTIC_OBSERVATIONS - 1
            if (posterior >= 0.5 || tailDiagnostic) {
                val bestCandidate = checkNotNull(best.candidate)
                val noChangePosterior = hypotheses
                    .filter { it.candidate == null }
                    .sumOf(::probability)
                SwitchingKalmanDiagnostics.log(
                    "day=${observation.dayNumber} candidate=${bestCandidate.dayNumber} " +
                        "modeGroup=${bestCandidate.resetMode} posterior=$posterior noChange=$noChangePosterior " +
                        "evidence=${best.evidence} previousDrift=${bestCandidate.previousDrift} " +
                        "drift=${best.state.drift} offset=${best.state.offset} mode=${bestCandidate.resetMode}",
                )
            }
            if (posterior >= config.changeCommitProbability && best.evidence >= config.regimeMinEvidence) {
                val candidate = checkNotNull(best.candidate)
                committed += DetectedSwitchingChangePoint(
                    dayNumber = candidate.dayNumber,
                    confirmationDayNumber = observation.dayNumber,
                    posteriorProbability = posterior,
                    previousDrift = candidate.previousDrift,
                    newDrift = best.state.drift,
                    observationOffset = best.state.offset,
                    boundaryState = candidate.boundaryState,
                )
                SwitchingKalmanDiagnostics.log(
                    "committed boundary=${candidate.dayNumber} confirmation=${observation.dayNumber} " +
                        "posterior=$posterior oldDrift=${candidate.previousDrift} " +
                        "newDrift=${best.state.drift} offset=${best.state.offset} mode=${candidate.resetMode} " +
                        "freePrior=${learnedFreeRunningDrift ?: config.driftPrior}",
                )
                if (abs(candidate.previousDrift) > ENTRAINED_DRIFT_BAND) {
                    estimateSpectralFreeRunningDrift(
                        sorted.filter { it.dayNumber in committedRegimeStart until candidate.dayNumber },
                    )?.let { learnedFreeRunningDrift = it }
                }
                committedRegimeStart = candidate.dayNumber
                hypotheses = normalizeAndPrune(
                    candidateHypotheses
                        .filter { checkNotNull(it.candidate).dayNumber == candidate.dayNumber }
                        .map { it.copy(candidate = null) },
                )
            }
        }
        previousDay = observation.dayNumber
    }
    return committed
}

private fun normalizeAndPrune(input: List<SwitchingHypothesis>): List<SwitchingHypothesis> {
    if (input.isEmpty()) return emptyList()
    val maximum = input.maxOf(SwitchingHypothesis::logProbability)
    val normalization = maximum + ln(input.sumOf { exp(it.logProbability - maximum) })
    return input.asSequence()
        .map { it.copy(logProbability = it.logProbability - normalization) }
        .sortedByDescending(SwitchingHypothesis::logProbability)
        .take(SWITCHING_BEAM_WIDTH)
        .toList()
        .let { retained ->
            val retainedMaximum = retained.maxOf(SwitchingHypothesis::logProbability)
            val retainedNormalization = retainedMaximum +
                ln(retained.sumOf { exp(it.logProbability - retainedMaximum) })
            retained.map { it.copy(logProbability = it.logProbability - retainedNormalization) }
        }
}

private fun probability(hypothesis: SwitchingHypothesis): Double = exp(hypothesis.logProbability)

private data class ResetMode(val label: String, val driftMean: Double, val weight: Double)

private fun resetModes(
    previousDrift: Double,
    learnedFreeRunningDrift: Double,
    config: SwitchingKalmanConfig,
): List<ResetMode> {
    val genericWeight = config.genericChangeWeight.coerceIn(1e-6, 1.0 - 1e-6)
    val offsetWeight = config.offsetChangeWeight.coerceIn(1e-6, 1.0 - genericWeight - 1e-6)
    val favoredWeight = 1.0 - genericWeight - offsetWeight
    val favored = if (abs(previousDrift) <= ENTRAINED_DRIFT_BAND) {
        ResetMode("release", learnedFreeRunningDrift, favoredWeight)
    } else {
        ResetMode("entrainment", 0.0, favoredWeight)
    }
    return listOf(
        favored,
        ResetMode("offset-only", previousDrift, offsetWeight),
        ResetMode("generic-plus", previousDrift + config.genericJumpScale, genericWeight / 2.0),
        ResetMode("generic-minus", previousDrift - config.genericJumpScale, genericWeight / 2.0),
    )
}

internal fun estimateSpectralFreeRunningDrift(observations: List<KalmanObservation>): Double? {
    if (observations.size < MIN_SPECTRAL_ANCHORS) return null
    val result = computePeriodogram(
        anchors = observations.map { observation ->
            PeriodogramAnchor(
                dayNumber = observation.dayNumber,
                midpointHour = observation.midpointHour - observation.dayNumber * 24.0,
                weight = observation.weight,
            )
        },
        // Branch proposals only need a broad favored tau. The chart's 0.01 h
        // resolution adds substantial work here without useful inference precision.
        options = PeriodogramOptions(minPeriod = 22.0, maxPeriod = 27.0, step = 0.05),
    )
    val peak = result.points
        .asSequence()
        .filter { abs(it.period - 24.0) >= MIN_FREE_RUNNING_DISTANCE_HOURS }
        .maxByOrNull { it.power }
        ?: return null
    if (peak.power <= result.significanceThreshold) return null
    return peak.period - 24.0
}

private const val ENTRAINED_DRIFT_BAND = 0.25
private const val MIN_FREE_RUNNING_DISTANCE_HOURS = 0.25
private const val MIN_SPECTRAL_ANCHORS = 30
private const val TAIL_DIAGNOSTIC_OBSERVATIONS = 30
