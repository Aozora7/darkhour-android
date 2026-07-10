package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
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

    private fun assertDrift(tau: Double, expectedDrift: Double) {
        val records = generateSyntheticRecords(SyntheticOptions(tau = tau, days = 120, noise = 0.0))
        val states = fit(records, records.first().dateOfSleep)
        assertTrue(abs(states.last().drift - expectedDrift) < 0.12)
    }

    private fun fit(records: List<one.aozora.darkhour.core.model.SleepRecord>, firstDate: java.time.LocalDate) =
        fitUnwrappedKalmanTrend(
            prepareKalmanAnchors(records, firstDate, firstDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                .map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) },
        )
}
