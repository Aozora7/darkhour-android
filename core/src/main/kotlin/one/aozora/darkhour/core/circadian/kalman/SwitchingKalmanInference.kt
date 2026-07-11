package one.aozora.darkhour.core.circadian.kalman

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
)

object SwitchingKalmanDiagnostics {
    @Volatile var logger: ((String) -> Unit)? = null
    internal fun log(message: String) { logger?.invoke(message) }
}

private data class CandidateBoundary(
    val dayNumber: Int,
    val previousDrift: Double,
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

    for (observation in sorted.drop(1)) {
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
            if (hypothesis.evidence >= config.regimeMinEvidence) {
                val reset = resetSwitchingState(predicted, config)
                val changed = updateSwitchingState(reset, observation, config)
                branches += SwitchingHypothesis(
                    state = changed.state,
                    regimeStartDay = observation.dayNumber,
                    evidence = observation.weight,
                    logProbability = hypothesis.logProbability + ln(hazard) + changed.logLikelihood,
                    candidate = CandidateBoundary(observation.dayNumber, hypothesis.state.drift),
                )
            }
        }
        hypotheses = normalizeAndPrune(branches)
        val candidateHypotheses = hypotheses.filter { it.candidate != null }
        val winning = candidateHypotheses
            .map { checkNotNull(it.candidate).dayNumber }
            .distinct()
            .map { center ->
                center to candidateHypotheses.filter {
                    kotlin.math.abs(checkNotNull(it.candidate).dayNumber - center) <= BOUNDARY_CREDIBLE_RADIUS_DAYS
                }
            }
            .maxByOrNull { (_, group) -> group.sumOf(::probability) }
        if (winning != null) {
            val posterior = winning.second.sumOf(::probability)
            val best = winning.second.maxBy(SwitchingHypothesis::logProbability)
            if (posterior >= 0.5) {
                SwitchingKalmanDiagnostics.log(
                    "day=${observation.dayNumber} candidate=${winning.first} posterior=$posterior " +
                        "evidence=${best.evidence} drift=${best.state.drift} offset=${best.state.offset}",
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
                )
                SwitchingKalmanDiagnostics.log(
                    "committed boundary=${candidate.dayNumber} confirmation=${observation.dayNumber} " +
                        "posterior=$posterior oldDrift=${candidate.previousDrift} " +
                        "newDrift=${best.state.drift} offset=${best.state.offset}",
                )
                hypotheses = normalizeAndPrune(
                    winning.second
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

private const val BOUNDARY_CREDIBLE_RADIUS_DAYS = 10
