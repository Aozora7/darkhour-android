package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.time.LocalDate
import org.junit.Test

class GroundTruthScoringTest {
    @Test
    fun scoringAdaptersUseCurrentRegistryDefaults() {
        val records = generateSyntheticRecords(SyntheticOptions(days = 45, tau = 24.5, noise = 0.2))
        val algorithms = listOf(
            CsfGroundTruthAlgorithm,
            UnwrappedKalmanGroundTruthAlgorithm,
            SwitchingKalmanGroundTruthAlgorithm,
        )

        algorithms.forEach { algorithm ->
            val expected = CircadianAlgorithmRegistry.analyze(records, algorithmId = algorithm.id)
                .toGroundTruthPrediction()
            assertEquals(expected, algorithm.analyze(records))
        }
    }

    @Test
    fun compactFixturesRetainTheManualOverlayAndControlPoints() {
        assumeTrue("Private ground-truth fixtures are not available", GroundTruthFixtures.isAvailable)
        val datasets = GroundTruthFixtures.loadAll()

        assertEquals(5, datasets.size)
        assertTrue(datasets.all { it.records.isNotEmpty() && it.overlay.isNotEmpty() && it.controlPoints.isNotEmpty() })
        assertTrue(datasets.all { it.records.all { record -> record.stageData.isEmpty() && record.stages == null } })
        assertTrue(datasets.all { it.annotationZone.id == "Europe/Riga" && it.records.all { record -> record.startZoneOffset != null } })
    }

    @Test
    fun implementationsCanBeScoredAgainstEveryManualOverlay() {
        assumeTrue("Private ground-truth fixtures are not available", GroundTruthFixtures.isAvailable)
        val algorithms = listOf(
            CsfGroundTruthAlgorithm,
            UnwrappedKalmanGroundTruthAlgorithm,
            SwitchingKalmanGroundTruthAlgorithm,
        )
        for (algorithm in algorithms) {
            for (dataset in GroundTruthFixtures.loadAll()) {
                val score = scoreAgainstGroundTruth(algorithm.analyze(dataset.records), dataset)
                println(score.summary(dataset.id, algorithm.id))

                assertTrue("${algorithm.id}/${dataset.id}: no overlapping scored days", score.pairedDays > 0)
                assertTrue("${algorithm.id}/${dataset.id}: non-finite score", score.meanAbsolutePhaseErrorHours.isFinite())
            }
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
    "GTRESULT\t$algorithmId\t$datasetId\tn=$pairedDays\tmean=${format("%.2f", meanAbsolutePhaseErrorHours)}h" +
        "\tmedian=${format("%.2f", medianAbsolutePhaseErrorHours)}h\tp90=${format("%.2f", p90AbsolutePhaseErrorHours)}h" +
        "\tbias=${format("%+.2f", signedMeanPhaseErrorHours)}h\ttau-delta=${format("%+.1f", tauDeltaMinutes)}min" +
        "\tstreaks=${divergenceStreaks.size}\tpenalty=${format("%.2f", driftPenalty.total)}"

private fun format(pattern: String, value: Double): String = String.format(java.util.Locale.ROOT, pattern, value)
