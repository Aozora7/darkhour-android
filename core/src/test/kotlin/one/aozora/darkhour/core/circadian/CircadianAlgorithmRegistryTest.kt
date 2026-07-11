package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircadianAlgorithmRegistryTest {
    @Test
    fun defaultsToKalmanAndResolvesOnlyDeclaredParameters() {
        val defaults = CircadianAlgorithmRegistry.resolvedValues(CircadianAlgorithmRegistry.CSF_ID, emptyMap())
        val overridden = CircadianAlgorithmRegistry.resolvedValues(
            CircadianAlgorithmRegistry.CSF_ID,
            mapOf("tau_prior" to 23.0, "unknown" to 99.0),
        )

        assertEquals(CircadianAlgorithmRegistry.KALMAN_ID, CircadianAlgorithmRegistry.defaultAlgorithm.id)
        assertEquals(5, defaults.size)
        assertEquals(23.0, overridden.getValue("tau_prior"), 0.0)
        assertEquals(defaults.getValue("phase_noise"), overridden.getValue("phase_noise"), 0.0)
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
    fun declaresKalmanChangeDetectionParameters() {
        val parameters = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.KALMAN_ID)
            .parameters.associateBy(CircadianNumericParameter::key)

        assertParameter(parameters.getValue("change_window_days"), 33.0, 7.0, 42.0, 34)
        assertParameter(parameters.getValue("change_min_anchors"), 9.0, 3.0, 14.0, 10)
        assertParameter(parameters.getValue("change_min_anchor_weight"), 0.80, 0.10, 0.90, 15)
        assertParameter(parameters.getValue("change_min_drift_delta"), 0.26, 0.10, 1.00, 17)
        assertParameter(parameters.getValue("change_fit_improvement"), 2.6, 1.1, 5.0, 38)
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
