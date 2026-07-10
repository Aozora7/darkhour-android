package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianNumericParameter
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthTauDivergenceTest {
    @Test
    fun exactDailyMovementHasNoTauDivergence() {
        val dataset = datasetWithDailyDrift(days = 60, driftHours = 1.0)
        val prediction = predictionWithDailyDrift(days = 60, driftHours = 1.0)

        val score = scoreTauDivergence(prediction, dataset, windowDays = 42)

        assertTrue(score.windows > 0)
        assertEquals(0.0, score.p90AbsoluteDeltaMinutesPerDay, 1e-9)
        assertEquals(0.0, score.maxAbsoluteDeltaMinutesPerDay, 1e-9)
    }

    @Test
    fun missingOneHourOfDailyDriftRemainsVisibleAcrossPhaseWraps() {
        val dataset = datasetWithDailyDrift(days = 60, driftHours = 1.0)
        val prediction = predictionWithDailyDrift(days = 60, driftHours = 0.0)

        val score = scoreTauDivergence(prediction, dataset, windowDays = 42)

        assertEquals(60.0, score.meanAbsoluteDeltaMinutesPerDay, 1e-9)
        assertEquals(60.0, score.p90AbsoluteDeltaMinutesPerDay, 1e-9)
        assertEquals(1.0, score.significantWindowFraction, 1e-9)
    }

    @Test
    fun objectivePrioritizesSustainedTauDivergenceOverSmallPhaseChanges() {
        val exactDataset = datasetWithDailyDrift(days = 60, driftHours = 1.0)
        val divergentDataset = datasetWithDailyDrift(days = 60, driftHours = 1.0)
        val exactPrediction = predictionWithDailyDrift(days = 60, driftHours = 1.0)
        val divergentPrediction = predictionWithDailyDrift(days = 60, driftHours = 0.0)
        val exact = DatasetTuningScore(
            "exact",
            scoreAgainstGroundTruth(exactPrediction, exactDataset),
            scoreTauDivergence(exactPrediction, exactDataset, 42),
        )
        val divergent = DatasetTuningScore(
            "divergent",
            scoreAgainstGroundTruth(divergentPrediction, divergentDataset),
            scoreTauDivergence(divergentPrediction, divergentDataset, 42),
        )

        assertTrue(tuningObjective(listOf(exact)).first < tuningObjective(listOf(divergent)).first)
    }

    @Test
    fun searchValuesAreQuantizedToRegisteredPrecision() {
        val dimension = SearchDimension(
            CircadianNumericParameter(
                key = "phase_noise",
                label = "Phase noise",
                defaultValue = 0.08,
                minValue = 0.01,
                maxValue = 0.50,
                steps = 49,
                decimalPlaces = 2,
            ),
        )

        listOf(0.0, 0.1234, 0.5, 0.9876, 1.0).forEach { normalized ->
            val value = dimension.decode(normalized)
            assertEquals(kotlin.math.round(value * 100.0), value * 100.0, 1e-9)
        }
    }

    private fun datasetWithDailyDrift(days: Int, driftHours: Double): GroundTruthDataset = GroundTruthDataset(
        id = "synthetic",
        annotationZone = ZoneOffset.UTC,
        records = emptyList(),
        overlay = (0 until days).map { day ->
            val midpoint = 6.0 + day * driftHours
            GroundTruthOverlayDay(START.plusDays(day.toLong()), midpoint - 4.0, midpoint + 4.0)
        },
        controlPoints = emptyList(),
    )

    private fun predictionWithDailyDrift(days: Int, driftHours: Double): List<GroundTruthPredictionDay> =
        (0 until days).map { day ->
            val midpoint = 6.0 + day * driftHours
            GroundTruthPredictionDay(START.plusDays(day.toLong()), midpoint - 4.0, midpoint + 4.0)
        }

    private companion object {
        val START: LocalDate = LocalDate.parse("2026-01-01")
    }
}
