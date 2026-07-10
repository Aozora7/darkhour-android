package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.CsfConfig
import one.aozora.darkhour.core.circadian.csf.analyzeCircadianCsf
import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
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
            CircadianNumericParameter("tau_prior", "Tau prior", 25.0, 22.0, 27.0, 100, 2, "h"),
            CircadianNumericParameter("phase_noise", "Phase noise", 0.08, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("tau_noise", "Tau noise", 0.001, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_kappa", "Observation weight", 0.35, 0.05, 1.00, 95, 2),
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
            CircadianNumericParameter("drift_prior", "Daily drift prior", 1.0, -1.5, 3.0, 90, 2, "h/d"),
            CircadianNumericParameter("phase_variance", "Phase variance", 0.08, 0.01, 0.50, 49, 2),
            CircadianNumericParameter("drift_variance", "Drift variance", 0.001, 0.0001, 0.02, 99, 4),
            CircadianNumericParameter("measurement_variance", "Measurement variance", 5.0, 0.25, 8.0, 31, 2),
            durationSmoothingParameter(),
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
                ),
            )
    }

    val algorithms: List<CircadianAlgorithmDefinition> = listOf(csf, kalman)
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
