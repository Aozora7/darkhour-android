package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord

const val ALGORITHM_ID = "csf-v1"
const val MIN_ANCHORS = 2
const val TAU_MIN = 22.0
const val TAU_MAX = 27.0

/**
 * Production CSF's heuristic state. [phase] is deliberately unwrapped in
 * hours, while [tau] is the implied cycle length in hours.  The variance and
 * covariance fields guide adaptive weighting but are not a fully calibrated
 * Gaussian covariance matrix; see the prototype package for that reference
 * formulation.
 */
data class CsfState(
    val phase: Double,
    val tau: Double,
    val phaseVar: Double,
    val tauVar: Double,
    val cov: Double,
)

data class TauPriorNoise(
    val forward: Double,
    val backward: Double,
    val none: Double,
)

data class CsfConfig(
    val processNoisePhase: Double,
    val processNoiseTau: Double,
    val measurementKappaBase: Double,
    val tauPrior: Double,
    val tauPriorVar: Double,
    val maxCorrectionPerStep: Double,
    val gateThreshold: Double,
    val tauPriorNoise: TauPriorNoise,
) {
    companion object {
        val Default = CsfConfig(
            processNoisePhase = 0.08,
            processNoiseTau = 0.001,
            measurementKappaBase = 0.35,
            tauPrior = 25.0,
            tauPriorVar = 0.1,
            maxCorrectionPerStep = 4.0,
            gateThreshold = 6.0,
            tauPriorNoise = TauPriorNoise(
                forward = 0.1,
                backward = 1.0,
                none = 5.0,
            ),
        )
    }
}

data class CsfAnchor(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
    val record: SleepRecord,
)

data class CsfAnalysis(
    override val globalTau: Double,
    override val globalDailyDrift: Double,
    override val days: List<CircadianDay>,
    override val algorithmId: String,
    override val tau: Double,
    override val dailyDrift: Double,
    override val rSquared: Double,
    val states: List<SmoothedState>,
    val anchorCount: Int,
) : CircadianAnalysis

data class SmoothedState(
    val phase: Double,
    val tau: Double,
    val phaseVar: Double,
    val tauVar: Double,
    val cov: Double,
    val smoothedPhase: Double,
    val smoothedTau: Double,
    val smoothedPhaseVar: Double,
    val smoothedTauVar: Double,
) {
    constructor(state: CsfState) : this(
        phase = state.phase,
        tau = state.tau,
        phaseVar = state.phaseVar,
        tauVar = state.tauVar,
        cov = state.cov,
        smoothedPhase = state.phase,
        smoothedTau = state.tau,
        smoothedPhaseVar = state.phaseVar,
        smoothedTauVar = state.tauVar,
    )

    fun withSmoothed(
        smoothedPhase: Double = this.smoothedPhase,
        smoothedTau: Double = this.smoothedTau,
        smoothedPhaseVar: Double = this.smoothedPhaseVar,
        smoothedTauVar: Double = this.smoothedTauVar,
    ) = copy(
        smoothedPhase = smoothedPhase,
        smoothedTau = smoothedTau,
        smoothedPhaseVar = smoothedPhaseVar,
        smoothedTauVar = smoothedTauVar,
    )
}

data class SegmentResult(
    val days: List<CircadianDay>,
    val states: List<SmoothedState>,
    val anchors: List<CsfAnchor>,
    val anchorCount: Int,
    val residuals: List<Double>,
    val segFirstDay: Int,
    val segLastDay: Int,
)
