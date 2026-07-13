package one.aozora.darkhour.core.circadian.adaptive

data class AdaptiveKalmanObservation(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
)

/** Shared Kalman state-space parameters used by production and experimental registrations. */
data class AdaptiveKalmanConfig(
    val driftPrior: Double = 0.52,
    val initialPhaseVariance: Double = 4.0,
    val initialDriftVariance: Double = 1.0,
    val processPhaseVariance: Double = 0.50,
    val processDriftVariance: Double = 0.0001,
    val measurementVarianceAtUnitWeight: Double = 9.77,
    val gateStandardDeviations: Double = 6.0,
) {
    init {
        require(driftPrior.isFinite())
        require(initialPhaseVariance > 0.0)
        require(initialDriftVariance > 0.0)
        require(processPhaseVariance > 0.0)
        require(processDriftVariance > 0.0)
        require(measurementVarianceAtUnitWeight > 0.0)
        require(gateStandardDeviations > 0.0)
    }
}

/** Optional transition layer. A null instance means detection cannot run. */
data class AdaptiveKalmanTransitionConfig(
    val evidenceWindowDays: Int = 42,
    val evidenceMinAnchors: Int = 8,
    val evidenceMinAnchorWeight: Double = 0.70,
    val evidenceMinDriftDelta: Double = 0.50,
    val evidenceFitImprovement: Double = 2.4,
    val evidenceMaxMeanHuberLoss: Double = 0.25,
    val evidenceMaxHalfSlopeDifference: Double = 0.60,
    val evidenceMaxAnchorGapDays: Int = 7,
    val minimumRegimeDays: Int = 45,
    val commitMinDriftDelta: Double = 0.85,
    val commitFitImprovement: Double = 5.0,
    val commitMaxMeanHuberLoss: Double = 0.008,
    val commitMaxHalfSlopeDifference: Double = 0.30,
    val transitionPhaseVariance: Double = 36.0,
    val transitionDriftVariance: Double = 0.01,
) {
    init {
        require(evidenceWindowDays >= 7)
        require(evidenceMinAnchors >= 5)
        require(evidenceMinAnchorWeight > 0.0)
        require(evidenceMinDriftDelta > 0.0)
        require(evidenceFitImprovement > 1.0)
        require(evidenceMaxMeanHuberLoss > 0.0)
        require(evidenceMaxHalfSlopeDifference > 0.0)
        require(evidenceMaxAnchorGapDays >= 1)
        require(minimumRegimeDays >= evidenceMinAnchors)
        require(commitMinDriftDelta > 0.0)
        require(commitFitImprovement > 1.0)
        require(commitMaxMeanHuberLoss > 0.0)
        require(commitMaxHalfSlopeDifference > 0.0)
        require(transitionPhaseVariance > 0.0)
        require(transitionDriftVariance > 0.0)
    }
}

data class AdaptiveKalmanState(
    val dayNumber: Int,
    val phase: Double,
    val drift: Double,
    val phaseVariance: Double,
    /** Strength of coherent abrupt-transition evidence applied at this boundary. */
    val transitionEvidence: Double,
)

data class AdaptiveKalmanTransition(
    val boundaryDay: Int,
    val confirmationDay: Int,
    val previousDrift: Double,
    val newDrift: Double,
    val boundaryPhase: Double,
    val evidence: Double,
    val committed: Boolean,
) {
    val confirmationLagDays: Int get() = confirmationDay - boundaryDay
}

data class AdaptiveKalmanFit(
    val states: List<AdaptiveKalmanState>,
    val transitions: List<AdaptiveKalmanTransition>,
)

object AdaptiveKalmanDiagnostics {
    @Volatile var logger: ((String) -> Unit)? = null
    internal fun log(message: String) { logger?.invoke(message) }
}
