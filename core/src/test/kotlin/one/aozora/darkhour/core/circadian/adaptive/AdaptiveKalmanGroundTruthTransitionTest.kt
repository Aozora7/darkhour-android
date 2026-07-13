package one.aozora.darkhour.core.circadian.adaptive

import java.time.Duration
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.groundtruth.GroundTruthFixtures
import one.aozora.darkhour.core.circadian.kalman.KalmanAnalysis
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AdaptiveKalmanGroundTruthTransitionTest {
    @Test
    fun reportsDetectorsAndRequiresAnnotatedAdaptiveTransitions() {
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
                    "\tadaptive=${adaptive.changePoints}",
            )
            EXPECTED_TRANSITIONS[dataset.id].orEmpty().forEach { expected ->
                val detected = adaptive.changePoints.minByOrNull { point -> absDays(point.date, expected.date) }
                assertTrue(
                    "${dataset.id}: no adaptive transition near ${expected.date}; got ${adaptive.changePoints}",
                    detected != null && absDays(checkNotNull(detected).date, expected.date) <= expected.maxBoundaryErrorDays,
                )
                val confirmationDelay = java.time.temporal.ChronoUnit.DAYS.between(
                    expected.date,
                    checkNotNull(detected).confirmationDate,
                )
                assertTrue(
                    "${dataset.id}: ${expected.date} was confirmed after $confirmationDelay days: $detected",
                    confirmationDelay in 0..expected.maxConfirmationDelayDays,
                )
            }
            if (dataset.id == "Toasty27-2020-10-20") {
                val stableIntervalEvents = adaptive.changePoints.count {
                    it.date in java.time.LocalDate.parse("2023-07-01")..java.time.LocalDate.parse("2023-12-01")
                }
                assertTrue(
                    "${dataset.id}: expected exactly the two annotated boundaries in the stable interval; " +
                        "got ${adaptive.changePoints}",
                    stableIntervalEvents == 2,
                )
            }
        }
    }
}

private fun absDays(first: java.time.LocalDate, second: java.time.LocalDate): Long =
    kotlin.math.abs(Duration.between(first.atStartOfDay(), second.atStartOfDay()).toDays())

private data class AnnotatedTransition(
    val date: java.time.LocalDate,
    val maxBoundaryErrorDays: Long = 16,
    val maxConfirmationDelayDays: Long = 28,
)

private val EXPECTED_TRANSITIONS = mapOf(
    "Toasty27-2020-10-20" to listOf(
        AnnotatedTransition(java.time.LocalDate.parse("2023-07-25")),
        AnnotatedTransition(java.time.LocalDate.parse("2023-11-10")),
    ),
    "Toasty27_2024-11-17_2026-02-11" to listOf(
        AnnotatedTransition(java.time.LocalDate.parse("2025-06-30")),
    ),
)
