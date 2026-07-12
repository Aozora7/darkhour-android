package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.CsfConfig
import one.aozora.darkhour.core.circadian.csf.analyzeCircadianCsf
import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
import one.aozora.darkhour.core.circadian.kalman.KalmanChangeDetectionConfig
import one.aozora.darkhour.core.circadian.kalman.SwitchingKalmanConfig
import one.aozora.darkhour.core.circadian.kalman.analyzeCircadianKalman
import one.aozora.darkhour.core.circadian.kalman.analyzeCircadianSwitchingKalman
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
    const val SWITCHING_KALMAN_ID = "switching-kalman-v1"

    private val csf = object : CircadianAlgorithmDefinition {
        override val id = CSF_ID
        override val displayName = "CSF"
        override val parameters = listOf(
            CircadianNumericParameter("tau_prior", "Tau prior", 25.41, 22.0, 27.0, 100, 2, "h"),
            CircadianNumericParameter("phase_noise", "Phase noise", 0.02, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("tau_noise", "Tau noise", 0.0028, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_kappa", "Observation weight", 0.6, 0.05, 1.00, 95, 2),
            durationSmoothingParameter(),
        )

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianCsf(
                records = records,
                extraDays = extraDays,
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
            CircadianNumericParameter("drift_prior", "Daily drift prior", 0.51, -1.5, 3.0, 90, 2, "h/d"),
            CircadianNumericParameter("phase_variance", "Phase variance", 0.49, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("drift_variance", "Drift variance", 0.0001, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_variance", "Measurement variance", 10.0, 0.25, 10.0, 31, 2),
            durationSmoothingParameter(),
            CircadianNumericParameter("change_window_days", "Change window", 20.0, 7.0, 42.0, 34, 0, "d"),
            CircadianNumericParameter("change_min_anchors", "Change anchors", 7.0, 3.0, 14.0, 10, 0),
            CircadianNumericParameter("change_min_anchor_weight", "Change anchor weight", 0.43, 0.10, 0.90, 15, 2),
            CircadianNumericParameter("change_min_drift_delta", "Change drift delta", 0.86, 0.10, 1.00, 17, 2, "h/d"),
            CircadianNumericParameter("change_fit_improvement", "Change fit improvement", 3.1, 1.1, 5.0, 38, 1, "×"),
        )

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianKalman(
                records = records,
                extraDays = extraDays,
                config = KalmanConfig(
                    driftPrior = values.valueOf("drift_prior"),
                    processPhaseVariance = values.valueOf("phase_variance"),
                    processDriftVariance = values.valueOf("drift_variance"),
                    measurementVarianceAtUnitWeight = values.valueOf("measurement_variance"),
                    durationSmoothing = DurationSmoothingConfig(values.valueOf("duration_smoothing_sigma")),
                    changeDetection = KalmanChangeDetectionConfig(
                        windowDays = values.valueOf("change_window_days").toInt(),
                        minAnchors = values.valueOf("change_min_anchors").toInt(),
                        minAnchorWeight = values.valueOf("change_min_anchor_weight"),
                        minDriftDelta = values.valueOf("change_min_drift_delta"),
                        fitImprovement = values.valueOf("change_fit_improvement"),
                    ),
                ),
            )
    }

    private val switchingKalman = object : CircadianAlgorithmDefinition {
        override val id = SWITCHING_KALMAN_ID
        override val displayName = "Switching Kalman (experimental)"
        override val parameters = listOf(
            CircadianNumericParameter("drift_prior", "Daily drift prior", 1.03, -1.5, 3.0, 90, 2, "h/d"),
            // Sets the initial free-running drift and the fallback target when a preferred release branch is created.
            CircadianNumericParameter("phase_variance", "Phase variance", 0.50, 0.01, 0.50, 49, 2),
            // Higher values let circadian phase respond more readily to day-to-day timing deviations.
            CircadianNumericParameter("drift_variance", "Drift variance", 0.0001, 0.0001, 0.02, 99, 4),
            // Higher values let tau change gradually within a regime instead of requiring a regime boundary.
            CircadianNumericParameter("measurement_variance", "Measurement variance", 6.5, 0.25, 10.0, 31, 2),
            // Higher values place less trust in each observed sleep midpoint.
            CircadianNumericParameter("regime_prior_days", "Generic regime prior", 306.0, 14.0, 365.0, 350, 0, "d"),
            // Higher values make arbitrary non-24 and sleep-offset regime changes less likely a priori.
            CircadianNumericParameter("regime_min_evidence", "Generic regime evidence", 9.2, 3.0, 14.0, 21, 1),
            // Sets the minimum accumulated anchor weight required to propose and commit a generic change.
            CircadianNumericParameter("drift_reset_variance", "Generic drift reset variance", 0.01, 0.01, 4.0, 99, 2),
            // Higher values let a generic change branch estimate a slope farther from its proposed jump target.
            CircadianNumericParameter("preferred_regime_prior_days", "Preferred regime prior", 60.0, 7.0, 180.0, 173, 0, "d"),
            // Higher values make transitions to or from 24-hour tau less likely a priori.
            CircadianNumericParameter("preferred_regime_min_evidence", "Preferred regime evidence", 7.5, 3.0, 10.0, 28, 1),
            // Sets the minimum accumulated anchor weight required to propose and commit a preferred change.
            CircadianNumericParameter("preferred_drift_reset_variance", "Preferred drift reset variance", 0.08, 0.01, 0.50, 49, 2),
            // Higher values let entrainment or release branches adapt farther from their favored tau target.
            CircadianNumericParameter("offset_reset_variance", "Offset reset variance", 0.69, 0.25, 36.0, 143, 2, "h²"),
            // Higher values allow a regime change to explain more timing relocation as sleep placement rather than phase.
            CircadianNumericParameter("offset_adaptation_days", "Offset adaptation", 6.0, 3.0, 42.0, 39, 0, "d"),
            // Higher values preserve the inferred sleep-placement offset longer before transferring it into phase.
            CircadianNumericParameter("change_commit_probability", "Generic change probability", 0.96, 0.60, 0.99, 38, 2),
            // Higher values require stronger posterior confidence before committing a generic boundary.
            CircadianNumericParameter("preferred_change_commit_probability", "Preferred change probability", 0.80, 0.60, 0.99, 38, 2),
            // Higher values require stronger posterior confidence before committing entrainment or release.
            CircadianNumericParameter("generic_change_weight", "Generic change weight", 0.11, 0.01, 0.50, 48, 2),
            // Higher values favor arbitrary slope-jump branches over offset-only branches within the generic hazard.
            CircadianNumericParameter("generic_jump_scale", "Generic jump scale", 0.66, 0.20, 1.50, 25, 2, "h/d"),
            // Sets the positive and negative drift displacement proposed by generic slope-jump branches.
            CircadianNumericParameter("offset_change_weight", "Offset change weight", 0.5, 0.01, 0.50, 48, 2),
            // Higher values favor offset-only branches over arbitrary slope jumps within the generic hazard.
            durationSmoothingParameter(),
            // Higher values smooth estimated sleep duration over more days without directly changing phase or tau.
        )

        override fun analyze(records: List<SleepRecord>, extraDays: Int, values: Map<String, Double>): CircadianAnalysis =
            analyzeCircadianSwitchingKalman(
                records = records,
                extraDays = extraDays,
                config = SwitchingKalmanConfig(
                    driftPrior = values.valueOf("drift_prior"),
                    processPhaseVariance = values.valueOf("phase_variance"),
                    processDriftVariance = values.valueOf("drift_variance"),
                    measurementVarianceAtUnitWeight = values.valueOf("measurement_variance"),
                    regimePriorDays = values.valueOf("regime_prior_days"),
                    regimeMinEvidence = values.valueOf("regime_min_evidence"),
                    driftResetVariance = values.valueOf("drift_reset_variance"),
                    preferredRegimePriorDays = values.valueOf("preferred_regime_prior_days"),
                    preferredRegimeMinEvidence = values.valueOf("preferred_regime_min_evidence"),
                    preferredDriftResetVariance = values.valueOf("preferred_drift_reset_variance"),
                    offsetResetVariance = values.valueOf("offset_reset_variance"),
                    offsetAdaptationDays = values.valueOf("offset_adaptation_days"),
                    changeCommitProbability = values.valueOf("change_commit_probability"),
                    preferredChangeCommitProbability = values.valueOf("preferred_change_commit_probability"),
                    genericChangeWeight = values.valueOf("generic_change_weight"),
                    genericJumpScale = values.valueOf("generic_jump_scale"),
                    offsetChangeWeight = values.valueOf("offset_change_weight"),
                    durationSmoothing = DurationSmoothingConfig(values.valueOf("duration_smoothing_sigma")),
                ),
            )
    }

    val algorithms: List<CircadianAlgorithmDefinition> = listOf(csf, kalman, switchingKalman)
    val defaultAlgorithm: CircadianAlgorithmDefinition = kalman

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
