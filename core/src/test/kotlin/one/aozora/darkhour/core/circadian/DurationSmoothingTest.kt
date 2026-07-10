package one.aozora.darkhour.core.circadian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationSmoothingTest {
    @Test
    fun shortDurationChangeIsDampenedButSustainedChangeIsTracked() {
        val briefChange = (0..120).map { day ->
            DurationObservation(day, if (day in 57..63) 4.0 else 8.0)
        }
        val sustainedChange = (0..240).map { day ->
            DurationObservation(day, if (day in 120..240) 4.0 else 8.0)
        }
        val config = DurationSmoothingConfig(sigmaDays = 14.0)

        val briefCenter = smoothDurations(briefChange, 60..60, config).single()
        val sustainedCenter = smoothDurations(sustainedChange, 180..180, config).single()

        assertTrue("A one-week change should not fully determine the width", briefCenter > 7.0)
        assertTrue("A sustained change should become the local width", sustainedCenter < 4.1)
    }

    @Test
    fun fallsBackToSegmentMeanOutsideLocalSupport() {
        val durations = smoothDurations(
            observations = listOf(DurationObservation(0, 6.0), DurationObservation(1, 10.0)),
            targetDays = 100..100,
            config = DurationSmoothingConfig(sigmaDays = 3.0),
        )

        assertEquals(8.0, durations.single(), 0.0)
    }
}
