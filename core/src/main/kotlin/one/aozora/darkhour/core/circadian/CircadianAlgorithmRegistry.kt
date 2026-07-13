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
    private val adaptiveDefaults = AdaptiveKalmanConfig()
    private val transitionDefaults = AdaptiveKalmanTransitionConfig()

    private val csf = object : CircadianAlgorithmDefinition {
        override val id = CSF_ID
        override val displayName = "CSF"
        override val parameters = listOf(
            CircadianNumericParameter("tau_prior", "Tau prior", 25.23, 22.0, 27.0, 100, 2, "h"),
            CircadianNumericParameter("phase_noise", "Phase noise", 0.05, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("tau_noise", "Tau noise", 0.0029, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_kappa", "Observation weight", 0.63, 0.05, 1.00, 95, 2),
            CircadianNumericParameter("smoothing_days", "Phase and tau smoothing", 5.0, 2.0, 14.0, 24, 1, "d"),
            durationSmoothingParameter(),
        )

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
        override val parameters = listOf(
            CircadianNumericParameter("drift_prior", "Daily drift prior", adaptiveDefaults.driftPrior, -1.5, 3.0, 90, 2, "h/d"),
            CircadianNumericParameter("phase_variance", "Phase variance", adaptiveDefaults.processPhaseVariance, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("drift_variance", "Drift variance", adaptiveDefaults.processDriftVariance, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_variance", "Measurement variance", adaptiveDefaults.measurementVarianceAtUnitWeight, 0.25, 10.0, 39, 2),
            durationSmoothingParameter(),
        )

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
        override val parameters = listOf(
            CircadianNumericParameter("drift_prior", "Daily drift prior", adaptiveDefaults.driftPrior, -1.5, 3.0, 90, 2, "h/d"),
            CircadianNumericParameter("phase_variance", "Phase variance", adaptiveDefaults.processPhaseVariance, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("drift_variance", "Drift variance", adaptiveDefaults.processDriftVariance, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_variance", "Measurement variance", adaptiveDefaults.measurementVarianceAtUnitWeight, 0.25, 10.0, 39, 2),
            CircadianNumericParameter("evidence_window_days", "Transition window", transitionDefaults.evidenceWindowDays.toDouble(), 7.0, 42.0, 35, 0, "d"),
            CircadianNumericParameter("evidence_min_anchors", "Transition anchors", transitionDefaults.evidenceMinAnchors.toDouble(), 5.0, 14.0, 9, 0),
            CircadianNumericParameter("evidence_min_anchor_weight", "Minimum anchor weight", transitionDefaults.evidenceMinAnchorWeight, 0.10, 0.90, 16, 2),
            CircadianNumericParameter("evidence_min_drift_delta", "Transition drift delta", transitionDefaults.evidenceMinDriftDelta, 0.20, 1.00, 16, 2, "h/d"),
            CircadianNumericParameter("evidence_fit_improvement", "Transition fit improvement", transitionDefaults.evidenceFitImprovement, 1.1, 5.0, 39, 1, "×"),
            CircadianNumericParameter("evidence_max_mean_loss", "Transition residual tolerance", transitionDefaults.evidenceMaxMeanHuberLoss, 0.005, 1.0, 199, 3),
            CircadianNumericParameter("evidence_max_half_slope_difference", "Transition half-slope tolerance", transitionDefaults.evidenceMaxHalfSlopeDifference, 0.10, 1.50, 28, 2, "h/d"),
            CircadianNumericParameter("evidence_max_anchor_gap_days", "Transition maximum anchor gap", transitionDefaults.evidenceMaxAnchorGapDays.toDouble(), 1.0, 14.0, 13, 0, "d"),
            CircadianNumericParameter("minimum_regime_days", "Minimum regime duration", transitionDefaults.minimumRegimeDays.toDouble(), 14.0, 120.0, 106, 0, "d"),
            CircadianNumericParameter("commit_min_drift_delta", "Commit drift delta", transitionDefaults.commitMinDriftDelta, 0.50, 1.50, 20, 2, "h/d"),
            CircadianNumericParameter("commit_fit_improvement", "Commit fit improvement", transitionDefaults.commitFitImprovement, 2.0, 10.0, 32, 1, "×"),
            CircadianNumericParameter("commit_max_mean_loss", "Commit residual tolerance", transitionDefaults.commitMaxMeanHuberLoss, 0.005, 0.10, 95, 3),
            CircadianNumericParameter("commit_max_half_slope_difference", "Commit half-slope tolerance", transitionDefaults.commitMaxHalfSlopeDifference, 0.10, 0.80, 28, 2, "h/d"),
            CircadianNumericParameter("transition_phase_variance", "Transition phase variance", transitionDefaults.transitionPhaseVariance, 0.50, 36.0, 71, 2),
            CircadianNumericParameter("transition_drift_variance", "Transition drift variance", transitionDefaults.transitionDriftVariance, 0.001, 0.10, 99, 3),
            durationSmoothingParameter(),
        )

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

    private fun durationSmoothingParameter() = CircadianNumericParameter(
        key = "duration_smoothing_sigma",
        label = "Duration smoothing",
        defaultValue = DEFAULT_DURATION_SMOOTHING_SIGMA_DAYS,
        minValue = 3.0,
        maxValue = 60.0,
        steps = 58,
        decimalPlaces = 0,
        unit = "d",
    )
}
