package one.aozora.darkhour.core.circadian.groundtruth

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthRegimeTransitionsTest {
    @Test
    fun exactTauStepHasZeroTransitionMovementError() {
        val dataset = steppedDataset()

        val score = scoreRegimeTransitions(prediction(postChangeDrift = 0.0), dataset)

        assertEquals(1, score.transitions)
        assertEquals(14, score.scoredDays)
        assertEquals(0.0, score.meanAbsoluteMovementErrorHours, 1e-9)
    }

    @Test
    fun estimatorThatContinuesOldTauIsPenalizedNearTransition() {
        val dataset = steppedDataset()

        val exact = scoreRegimeTransitions(prediction(postChangeDrift = 0.0), dataset)
        val missed = scoreRegimeTransitions(prediction(postChangeDrift = 1.0), dataset)

        assertTrue(missed.meanAbsoluteMovementErrorHours > 5.0)
        assertTrue(missed.p90AbsoluteMovementErrorHours > exact.p90AbsoluteMovementErrorHours)
    }

    @Test
    fun transitionMovementErrorContributesToTuningObjective() {
        val dataset = steppedDataset()
        val exactPrediction = prediction(postChangeDrift = 0.0)
        val sharedPhase = scoreAgainstGroundTruth(exactPrediction, dataset)
        val sharedTau = scoreTauDivergence(exactPrediction, dataset, windowDays = 14)
        val exact = DatasetTuningScore("exact", sharedPhase, sharedTau, sharedPhase.regimeTransitions)
        val missed = DatasetTuningScore(
            "missed",
            sharedPhase,
            sharedTau,
            scoreRegimeTransitions(prediction(postChangeDrift = 1.0), dataset),
        )

        assertTrue(tuningObjective(listOf(exact)).first < tuningObjective(listOf(missed)).first)
    }

    @Test
    fun constantPhaseOffsetDoesNotAffectTransitionScore() {
        val dataset = steppedDataset()

        val score = scoreRegimeTransitions(prediction(postChangeDrift = 0.0, phaseOffset = 5.0), dataset)

        assertEquals(0.0, score.meanAbsoluteMovementErrorHours, 1e-9)
    }

    @Test
    fun unchangedTauDoesNotCreateTransitionTargets() {
        val overlay = timingDays(preChangeDrift = 1.0, postChangeDrift = 1.0)
        val dataset = steppedDataset().copy(overlay = overlay)

        val score = scoreRegimeTransitions(prediction(postChangeDrift = 1.0), dataset)

        assertEquals(0, score.transitions)
        assertEquals(0, score.scoredDays)
    }

    private fun steppedDataset() = GroundTruthDataset(
        id = "step",
        annotationZone = ZoneOffset.UTC,
        records = emptyList(),
        overlay = timingDays(preChangeDrift = 1.0, postChangeDrift = 0.0),
        controlPoints = listOf(
            GroundTruthControlPoint(START, 6.0),
            GroundTruthControlPoint(START.plusDays(BOUNDARY.toLong()), 26.0),
            GroundTruthControlPoint(START.plusDays(49), 26.0),
        ),
    )

    private fun prediction(postChangeDrift: Double, phaseOffset: Double = 0.0): List<GroundTruthPredictionDay> =
        timingDays(preChangeDrift = 1.0, postChangeDrift = postChangeDrift).map {
            GroundTruthPredictionDay(
                date = it.date,
                nightStartHour = it.nightStartHour + phaseOffset,
                nightEndHour = it.nightEndHour + phaseOffset,
            )
        }

    private fun timingDays(preChangeDrift: Double, postChangeDrift: Double): List<GroundTruthOverlayDay> =
        (0 until 50).map { day ->
            val midpoint = 6.0 + minOf(day, BOUNDARY) * preChangeDrift +
                maxOf(0, day - BOUNDARY) * postChangeDrift
            GroundTruthOverlayDay(START.plusDays(day.toLong()), midpoint - 4.0, midpoint + 4.0)
        }

    private companion object {
        const val BOUNDARY = 20
        val START: LocalDate = LocalDate.parse("2026-01-01")
    }
}
