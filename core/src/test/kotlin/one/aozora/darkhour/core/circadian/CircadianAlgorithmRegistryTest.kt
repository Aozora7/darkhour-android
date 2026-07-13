package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class CircadianAlgorithmRegistryTest {

    @Test
    fun loadsParametersFromTheResourceInDeclaredOrder() {
        assertEquals(
            listOf("tau_prior", "phase_noise", "tau_noise", "measurement_kappa", "smoothing_days", "duration_smoothing_sigma"),
            CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.CSF_ID).parameters.map { it.key },
        )
        assertEquals(
            listOf("drift_prior", "phase_variance", "drift_variance", "measurement_variance", "duration_smoothing_sigma"),
            CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.KALMAN_ID).parameters.map { it.key },
        )

        CircadianAlgorithmRegistry.algorithms.forEach { definition ->
            assertEquals(definition.parameters.size, definition.parameters.map { it.key }.toSet().size)
            assertEquals(
                DEFAULT_DURATION_SMOOTHING_SIGMA_DAYS,
                definition.parameters.single { it.key == "duration_smoothing_sigma" }.defaultValue,
                0.0,
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateParameterKeysInAResource() {
        CircadianAlgorithmParameters.parse(
            StringReader(
                "test-v1\ttau\tTau\t24.0\t22.0\t28.0\t60\t1\th\n" +
                    "test-v1\ttau\tTau again\t25.0\t22.0\t28.0\t60\t1\th",
            ),
        )
    }

    @Test
    fun clampsOverridesToTheParameterRange() {
        val values = CircadianAlgorithmRegistry.resolvedValues(
            CircadianAlgorithmRegistry.KALMAN_ID,
            mapOf("measurement_variance" to 99.0),
        )

        assertEquals(10.0, values.getValue("measurement_variance"), 0.0)
    }

    @Test
    fun executesEveryRegisteredAlgorithm() {
        val records = generateSyntheticRecords(SyntheticOptions(days = 45, tau = 24.5, noise = 0.0))

        CircadianAlgorithmRegistry.algorithms.forEach { definition ->
            val analysis = CircadianAlgorithmRegistry.analyze(records, algorithmId = definition.id)
            assertEquals(definition.id, analysis.algorithmId)
            assertTrue(analysis.days.isNotEmpty())
            assertTrue(analysis.days.all { it.localTau.isFinite() && it.nightStartHour.isFinite() })
        }
    }

    @Test
    fun adaptiveDetectionAndCommitOverridesCanBeTunedIndependently() {
        val records = generateSyntheticRecords(SyntheticOptions(days = 120, tau = 25.0, noise = 0.2))

        val analysis = CircadianAlgorithmRegistry.analyze(
            records = records,
            algorithmId = CircadianAlgorithmRegistry.ADAPTIVE_KALMAN_ID,
            overrides = mapOf(
                "evidence_min_drift_delta" to 1.0,
                "commit_min_drift_delta" to 0.5,
                "evidence_fit_improvement" to 5.0,
                "commit_fit_improvement" to 2.0,
                "evidence_max_mean_loss" to 0.01,
                "commit_max_mean_loss" to 0.10,
                "evidence_max_half_slope_difference" to 0.20,
                "commit_max_half_slope_difference" to 0.80,
            ),
        )

        assertTrue(analysis.days.isNotEmpty())
    }

    @Test
    fun productionAndExperimentalKalmanShareBaseDynamicsWithoutATransition() {
        val records = generateSyntheticRecords(SyntheticOptions(days = 120, tau = 24.5, noise = 0.2))

        val production = CircadianAlgorithmRegistry.analyze(
            records,
            algorithmId = CircadianAlgorithmRegistry.KALMAN_ID,
        )
        val experimental = CircadianAlgorithmRegistry.analyze(
            records,
            algorithmId = CircadianAlgorithmRegistry.ADAPTIVE_KALMAN_ID,
        )

        assertEquals(production.days, experimental.days)
        assertEquals(production.globalTau, experimental.globalTau, 0.0)
    }

}

private fun assertParameter(
    parameter: CircadianNumericParameter,
    default: Double,
    minimum: Double,
    maximum: Double,
    steps: Int,
) {
    assertEquals(default, parameter.defaultValue, 0.0)
    assertEquals(minimum, parameter.minValue, 0.0)
    assertEquals(maximum, parameter.maxValue, 0.0)
    assertEquals(steps, parameter.steps)
}
