package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.circadian.prepareWeightedMidpointAnchors
import java.time.ZoneOffset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnwrappedKalmanTrendTest {
    @Test
    fun recoversStablePositiveAndNegativeDailyDrift() {
        assertDrift(tau = 24.0, expectedDrift = 0.0)
        assertDrift(tau = 24.5, expectedDrift = 0.5)
        assertDrift(tau = 23.5, expectedDrift = -0.5)
    }

    @Test
    fun keepsTheMidpointContinuousAcrossMidnight() {
        val records = generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 60, noise = 0.0))
        val firstDate = records.first().dateOfSleep
        val states = fit(records, firstDate)

        assertEquals(60, states.size)
        assertTrue(states.zipWithNext().all { (a, b) -> abs((b.phase - a.phase) - 0.5) < 0.15 })
    }

    @Test
    fun terminalDriftObservationChangesOnlyEndpointAndForecast() {
        val observations = (0..39).map { day -> KalmanObservation(day, 3.0 + 0.8 * day, 1.0) }
        val baseline = fitUnwrappedKalmanTrendWithResets(
            observations,
            firstDay = 0,
            lastDay = 46,
            config = KalmanConfig(),
        )
        val updated = fitUnwrappedKalmanTrendWithResets(
            observations,
            firstDay = 0,
            lastDay = 46,
            config = KalmanConfig(),
            terminalDriftObservation = KalmanDriftObservation(
                dayNumber = 39,
                drift = 0.0,
                variance = 1e-6,
            ),
        )

        baseline.take(39).zip(updated.take(39)).forEach { (before, after) ->
            assertEquals(before.phase, after.phase, 0.0)
            assertEquals(before.drift, after.drift, 0.0)
        }
        assertEquals("terminal phase moved", baseline[39].phase, updated[39].phase, 0.0)
        assertTrue("endpoint drift was ${updated[39].drift}", abs(updated[39].drift) < 0.01)
        assertTrue(updated.drop(39).zipWithNext().all { (a, b) -> abs(b.phase - a.phase) < 0.01 })
    }

    private fun assertDrift(tau: Double, expectedDrift: Double) {
        val records = generateSyntheticRecords(SyntheticOptions(tau = tau, days = 120, noise = 0.0))
        val states = fit(records, records.first().dateOfSleep)
        assertTrue(abs(states.last().drift - expectedDrift) < 0.12)
    }

    private fun fit(records: List<one.aozora.darkhour.core.model.SleepRecord>, firstDate: java.time.LocalDate) =
        fitUnwrappedKalmanTrend(
            prepareWeightedMidpointAnchors(
                records,
                firstDate,
                firstDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            )
                .map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) },
        )
}
