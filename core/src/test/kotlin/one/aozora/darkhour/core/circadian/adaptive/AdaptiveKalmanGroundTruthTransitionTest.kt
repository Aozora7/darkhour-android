package one.aozora.darkhour.core.circadian.adaptive

import java.time.Duration
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.groundtruth.GroundTruthFixtures
import one.aozora.darkhour.core.circadian.kalman.KalmanAnalysis
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class AdaptiveKalmanGroundTruthTransitionTest {
    @Test
    fun reportsTransitionAgreementWithLegacyDetectorOnManualDatasets() {
        assumeTrue("Private ground-truth fixtures are not available", GroundTruthFixtures.isAvailable)
        val legacyDefinition = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.KALMAN_ID)
        val adaptiveDefinition = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.ADAPTIVE_KALMAN_ID)
        GroundTruthFixtures.loadAll().forEach { dataset ->
            val legacy = legacyDefinition.analyze(
                dataset.records,
                0,
                CircadianAlgorithmRegistry.resolvedValues(legacyDefinition.id, emptyMap()),
            ) as KalmanAnalysis
            val adaptive = adaptiveDefinition.analyze(
                dataset.records,
                0,
                CircadianAlgorithmRegistry.resolvedValues(adaptiveDefinition.id, emptyMap()),
            ) as AdaptiveKalmanAnalysis
            println(
                "TRANSITION_AGREEMENT\t${dataset.id}" +
                    "\tlegacy=${legacy.changePoints.map { it.date to it.confirmationDate }}" +
                    "\tadaptive=${adaptive.changePoints.map { it.date to it.confirmationDate }}",
            )
            assertEquals(
                "${dataset.id}: transition dates differ",
                legacy.changePoints.map { it.date },
                adaptive.changePoints.map { it.date },
            )
            adaptive.changePoints.forEach { adaptivePoint ->
                assertTrue(
                    "${dataset.id}: adaptive transition $adaptivePoint has no nearby legacy transition",
                    legacy.changePoints.any { legacyPoint ->
                        absDays(legacyPoint.date, adaptivePoint.date) <= MAX_BOUNDARY_DIFFERENCE_DAYS
                    },
                )
            }
        }
    }
}

private fun absDays(first: java.time.LocalDate, second: java.time.LocalDate): Long =
    kotlin.math.abs(Duration.between(first.atStartOfDay(), second.atStartOfDay()).toDays())

private const val MAX_BOUNDARY_DIFFERENCE_DAYS = 2L
