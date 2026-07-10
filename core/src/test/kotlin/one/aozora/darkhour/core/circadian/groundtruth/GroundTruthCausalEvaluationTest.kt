package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthCausalEvaluationTest {
    @Test
    fun normalizedExactForecastMatchesUnwrappedOverlay() {
        val expected = overlay(driftHours = 1.0)
        val prediction = expected.map { day ->
            val midpoint = normalize((day.nightStartHour + day.nightEndHour) / 2.0)
            GroundTruthPredictionDay(
                date = day.date,
                nightStartHour = midpoint - 4.0,
                nightEndHour = midpoint + 4.0,
                isForecast = true,
            )
        }

        val score = scoreCausalForecastWindow("exact", CUTOFF, HORIZON, prediction, expected)

        requireNotNull(score)
        assertEquals(0.0, score.circularPhaseMaeHours, 1e-9)
        assertEquals(0.0, score.unwrappedPhaseMaeHours, 1e-9)
        assertEquals(0.0, score.cumulativeMovementErrorHours, 1e-9)
    }

    @Test
    fun wrongTauPreservesMissingCycleErrorAfterClockTimeWraps() {
        val expected = overlay(driftHours = 1.0)
        val prediction = overlay(driftHours = 0.0).map { day ->
            GroundTruthPredictionDay(day.date, day.nightStartHour, day.nightEndHour, isForecast = true)
        }

        val score = scoreCausalForecastWindow("wrong-tau", CUTOFF, HORIZON, prediction, expected)

        requireNotNull(score)
        assertEquals(-(HORIZON - 1).toDouble(), score.cumulativeMovementErrorHours, 1e-9)
        assertEquals(-60.0, score.tauDeltaMinutesPerDay, 1e-9)
        assertTrue(kotlin.math.abs(score.endpointPhaseErrorHours) > 24.0)
    }

    @Test
    fun incompleteForecastIsNotSilentlyScored() {
        val expected = overlay(driftHours = 1.0)
        val prediction = expected.dropLast(1).map { day ->
            GroundTruthPredictionDay(day.date, day.nightStartHour, day.nightEndHour, isForecast = true)
        }

        assertNull(scoreCausalForecastWindow("missing", CUTOFF, HORIZON, prediction, expected))
    }

    @Test
    fun historicalRecordsExcludeEveryPostCutoffSession() {
        val records = listOf(
            sleepRecord(1, CUTOFF.minusDays(1)),
            sleepRecord(2, CUTOFF),
            sleepRecord(3, CUTOFF.plusDays(1)),
        )

        val history = historicalRecords(records, CUTOFF)

        assertEquals(listOf(1L, 2L), history.map(SleepRecord::logId))
        assertTrue(history.all { it.dateOfSleep <= CUTOFF })
    }

    private fun overlay(driftHours: Double): List<GroundTruthOverlayDay> = (1..HORIZON).map { offset ->
        val midpoint = 6.0 + (offset - 1) * driftHours
        GroundTruthOverlayDay(CUTOFF.plusDays(offset.toLong()), midpoint - 4.0, midpoint + 4.0)
    }

    private fun normalize(hour: Double): Double = ((hour % 24.0) + 24.0) % 24.0

    private fun sleepRecord(id: Long, date: LocalDate): SleepRecord {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        return SleepRecord(
            logId = id,
            dateOfSleep = date,
            startTime = start,
            endTime = start.plusSeconds(8 * 60 * 60),
            durationMs = 8 * 60 * 60 * 1_000L,
            durationHours = 8.0,
            efficiency = 90,
            minutesAsleep = 450,
            minutesAwake = 30,
            isMainSleep = true,
        )
    }

    private companion object {
        const val HORIZON = 42
        val CUTOFF: LocalDate = LocalDate.parse("2026-01-01")
    }
}
