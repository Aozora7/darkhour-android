package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

class GroundTruthScoringTest {
    @Test
    fun scoringAdaptersUseCurrentRegistryDefaults() {
        val records = generateSyntheticRecords(SyntheticOptions(days = 45, tau = 24.5, noise = 0.2))
        val algorithms = listOf(
            CsfGroundTruthAlgorithm,
            UnwrappedKalmanGroundTruthAlgorithm,
            AdaptiveKalmanGroundTruthAlgorithm,
        )

        algorithms.forEach { algorithm ->
            val expected = CircadianAlgorithmRegistry.analyze(records, algorithmId = algorithm.id)
                .toGroundTruthPrediction()
            assertEquals(expected, algorithm.analyze(records))
        }
    }

    @Test
    fun exactPredictionHasZeroPhaseAndTauError() {
        val days = (0..2).map { day ->
            val date = LocalDate.parse("2026-01-01").plusDays(day.toLong())
            GroundTruthOverlayDay(date, 2.0 + day, 10.0 + day)
        }
        val dataset = GroundTruthDataset("exact", java.time.ZoneOffset.UTC, emptyList(), days, emptyList())
        val prediction = days.map { day ->
            GroundTruthPredictionDay(day.date, day.nightStartHour, day.nightEndHour)
        }

        val score = scoreAgainstGroundTruth(prediction, dataset)

        assertEquals(0.0, score.meanAbsolutePhaseErrorHours, 0.0)
        assertEquals(0.0, score.tauDeltaMinutes, 0.0)
    }
}

internal fun GroundTruthScore.summary(datasetId: String, algorithmId: String): String =
    String.format(
        java.util.Locale.ROOT,
        "GTRESULT  %-22s %-38s n=%4d  mean=%5.2fh  median=%5.2fh  p90=%5.2fh  " +
            "bias=%+5.2fh  tau-delta=%+6.1fmin  transition=%5.2fh/%2d  streaks=%2d  penalty=%7.2f",
        algorithmId,
        datasetId,
        pairedDays,
        meanAbsolutePhaseErrorHours,
        medianAbsolutePhaseErrorHours,
        p90AbsolutePhaseErrorHours,
        signedMeanPhaseErrorHours,
        tauDeltaMinutes,
        regimeTransitions.meanAbsoluteMovementErrorHours,
        regimeTransitions.transitions,
        divergenceStreaks.size,
        driftPenalty.total,
    )
