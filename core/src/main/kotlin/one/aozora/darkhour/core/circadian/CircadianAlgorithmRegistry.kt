package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.CsfConfig
import one.aozora.darkhour.core.circadian.csf.CsfSmoothingConfig
import one.aozora.darkhour.core.circadian.csf.analyzeCircadianCsf
import one.aozora.darkhour.core.circadian.adaptive.AdaptiveKalmanConfig
import one.aozora.darkhour.core.circadian.adaptive.AdaptiveKalmanTransitionConfig
import one.aozora.darkhour.core.circadian.adaptive.analyzeCircadianAdaptiveKalman
import one.aozora.darkhour.core.model.SleepRecord

/** UI-neutral description of one tunable numeric algorithm parameter. */
data class CircadianNumericParameter(
    val key: String,
    val label: String,
    val defaultValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val steps: Int,
    val decimalPlaces: Int,
    val unit: String = "",
) {
    init {
        require(minValue < maxValue)
        require(defaultValue in minValue..maxValue)
        require(steps >= 0)
        require(decimalPlaces >= 0)
    }
}

/** Everything the app needs to present and execute one circadian algorithm. */
interface CircadianAlgorithmDefinition {
    val id: String
    val displayName: String
    val parameters: List<CircadianNumericParameter>

    fun analyze(
        records: List<SleepRecord>,
        extraDays: Int,
        values: Map<String, Double>,
    ): CircadianAnalysis
}

/**
 * Central catalog for selectable circadian estimators.  It intentionally keeps
 * parameter metadata and typed-config construction beside the algorithm, so a
 * generic developer UI never needs algorithm-specific branches.
 */
object CircadianAlgorithmRegistry {
    const val CSF_ID = "csf-v1"

    const val KALMAN_ID = "unwrapped-kalman-v1"
    const val ADAPTIVE_KALMAN_ID = "adaptive-kalman-v1"

    private val csf = object : CircadianAlgorithmDefinition {
        override val id = CSF_ID
        override val displayName = "CSF"
        override val parameters = CircadianAlgorithmParameters.forAlgorithm(id)

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianCsf(
                records = records,
                extraDays = extraDays,
                smoothing = CsfSmoothingConfig(values.valueOf("smoothing_days")),
                config = CsfConfig.Default.copy(
                    tauPrior = values.valueOf("tau_prior"),
                    processNoisePhase = values.valueOf("phase_noise"),
                    processNoiseTau = values.valueOf("tau_noise"),
                    measurementKappaBase = values.valueOf("measurement_kappa"),
                    durationSmoothing = DurationSmoothingConfig(values.valueOf("duration_smoothing_sigma")),
                ),
            )
    }

    private val kalman = object : CircadianAlgorithmDefinition {
        override val id = KALMAN_ID
        override val displayName = "Kalman"
        override val parameters = CircadianAlgorithmParameters.forAlgorithm(id)

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianAdaptiveKalman(
                records = records,
                extraDays = extraDays,
                config = AdaptiveKalmanConfig(
                    driftPrior = values.valueOf("drift_prior"),
                    processPhaseVariance = values.valueOf("phase_variance"),
                    processDriftVariance = values.valueOf("drift_variance"),
                    measurementVarianceAtUnitWeight = values.valueOf("measurement_variance"),
                ),
                transitionConfig = null,
                durationSmoothing = DurationSmoothingConfig(values.valueOf("duration_smoothing_sigma")),
                algorithmId = KALMAN_ID,
            )
    }

    private val adaptiveKalman = object : CircadianAlgorithmDefinition {
        override val id = ADAPTIVE_KALMAN_ID
        override val displayName = "Kalman + change detection (experimental)"
        override val parameters = CircadianAlgorithmParameters.forAlgorithm(id)

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianAdaptiveKalman(
                records = records,
                extraDays = extraDays,
                config = AdaptiveKalmanConfig(
                    driftPrior = values.valueOf("drift_prior"),
                    processPhaseVariance = values.valueOf("phase_variance"),
                    processDriftVariance = values.valueOf("drift_variance"),
                    measurementVarianceAtUnitWeight = values.valueOf("measurement_variance"),
                ),
                transitionConfig = AdaptiveKalmanTransitionConfig(
                    evidenceWindowDays = values.valueOf("evidence_window_days").toInt(),
                    evidenceMinAnchors = values.valueOf("evidence_min_anchors").toInt(),
                    evidenceMinAnchorWeight = values.valueOf("evidence_min_anchor_weight"),
                    evidenceMinDriftDelta = values.valueOf("evidence_min_drift_delta"),
                    evidenceFitImprovement = values.valueOf("evidence_fit_improvement"),
                    evidenceMaxMeanHuberLoss = values.valueOf("evidence_max_mean_loss"),
                    evidenceMaxHalfSlopeDifference = values.valueOf("evidence_max_half_slope_difference"),
                    evidenceMaxAnchorGapDays = values.valueOf("evidence_max_anchor_gap_days").toInt(),
                    minimumRegimeDays = values.valueOf("minimum_regime_days").toInt(),
                    commitMinDriftDelta = values.valueOf("commit_min_drift_delta"),
                    commitFitImprovement = values.valueOf("commit_fit_improvement"),
                    commitMaxMeanHuberLoss = values.valueOf("commit_max_mean_loss"),
                    commitMaxHalfSlopeDifference = values.valueOf("commit_max_half_slope_difference"),
                    transitionPhaseVariance = values.valueOf("transition_phase_variance"),
                    transitionDriftVariance = values.valueOf("transition_drift_variance"),
                ),
                durationSmoothing = DurationSmoothingConfig(values.valueOf("duration_smoothing_sigma")),
                algorithmId = ADAPTIVE_KALMAN_ID,
            )
    }

    val algorithms: List<CircadianAlgorithmDefinition> = listOf(csf, kalman, adaptiveKalman)
    val defaultAlgorithm: CircadianAlgorithmDefinition = csf

    fun algorithm(id: String): CircadianAlgorithmDefinition =
        algorithms.firstOrNull { it.id == id } ?: defaultAlgorithm

    fun resolvedValues(algorithmId: String, overrides: Map<String, Double>): Map<String, Double> {
        val definition = algorithm(algorithmId)
        return definition.parameters.associate { parameter ->
            val value = overrides[parameter.key]
                ?.takeIf { it.isFinite() }
                ?.coerceIn(parameter.minValue, parameter.maxValue)
                ?: parameter.defaultValue
            parameter.key to value
        }
    }

    fun analyze(
        records: List<SleepRecord>,
        extraDays: Int = 0,
        algorithmId: String = defaultAlgorithm.id,
        overrides: Map<String, Double> = emptyMap(),
    ): CircadianAnalysis {
        val definition = algorithm(algorithmId)
        return definition.analyze(records, extraDays, resolvedValues(definition.id, overrides))
            .withNormalizedOverlayConfidence()
    }

    private fun Map<String, Double>.valueOf(key: String): Double = checkNotNull(this[key])
}
