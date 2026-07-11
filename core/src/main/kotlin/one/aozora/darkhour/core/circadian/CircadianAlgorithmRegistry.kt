package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.CsfConfig
import one.aozora.darkhour.core.circadian.csf.analyzeCircadianCsf
import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
import one.aozora.darkhour.core.circadian.kalman.KalmanChangeDetectionConfig
import one.aozora.darkhour.core.circadian.kalman.analyzeCircadianKalman
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
            CircadianNumericParameter("drift_prior", "Daily drift prior", 0.9, -1.5, 3.0, 90, 2, "h/d"),
            CircadianNumericParameter("phase_variance", "Phase variance", 0.49, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("drift_variance", "Drift variance", 0.0001, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_variance", "Measurement variance", 10.0, 0.25, 10.0, 31, 2),
            durationSmoothingParameter(),
            CircadianNumericParameter("change_window_days", "Change window", 33.0, 7.0, 42.0, 34, 0, "d"),
            CircadianNumericParameter("change_min_anchors", "Change anchors", 9.0, 3.0, 14.0, 10, 0),
            CircadianNumericParameter("change_min_anchor_weight", "Change anchor weight", 0.80, 0.10, 0.90, 15, 2),
            CircadianNumericParameter("change_min_drift_delta", "Change drift delta", 0.26, 0.10, 1.00, 17, 2, "h/d"),
            CircadianNumericParameter("change_fit_improvement", "Change fit improvement", 2.6, 1.1, 5.0, 38, 1, "×"),
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

    val algorithms: List<CircadianAlgorithmDefinition> = listOf(csf, kalman)
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
