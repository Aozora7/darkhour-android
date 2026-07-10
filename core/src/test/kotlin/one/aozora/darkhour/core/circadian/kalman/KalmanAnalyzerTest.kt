package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import java.time.Duration
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalmanAnalyzerTest {
    @Test
    fun producesAppCompatibleAnalysisForAStableSyntheticTrend() {
        val analysis = analyzeCircadianKalman(
            generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 90, noise = 0.0)),
            extraDays = 7,
        )

        assertEquals(KALMAN_ALGORITHM_ID, analysis.algorithmId)
        assertEquals(7, analysis.days.count { it.isForecast })
        assertTrue(analysis.days.none { !it.nightStartHour.isFinite() || !it.nightEndHour.isFinite() })
        assertTrue(analysis.days.all { it.nightStartHour in -12.0..24.0 && it.nightEndHour in 0.0..36.0 })
        assertTrue(abs(analysis.globalTau - 24.5) < 0.2)
    }

    @Test
    fun forecastsOnlyBeyondTheLastValidAnchor() {
        val anchored = generateSyntheticRecords(SyntheticOptions(days = 10, noise = 0.0))
        val trailingUnanchored = (1..12).map { day ->
            val source = anchored.last()
            source.copy(
                logId = source.logId + day,
                dateOfSleep = source.dateOfSleep.plusDays(day.toLong()),
                startTime = source.startTime.plus(Duration.ofDays(day.toLong())),
                endTime = source.startTime.plus(Duration.ofDays(day.toLong())).plus(Duration.ofHours(3)),
                durationMs = Duration.ofHours(3).toMillis(),
                durationHours = 3.0,
                minutesAsleep = 180,
                minutesAwake = 0,
            )
        }

        val analysis = analyzeCircadianKalman(anchored + trailingUnanchored, extraDays = 3)
        val nonGapDays = analysis.days.filterNot { it.isGap }

        assertEquals(3, analysis.days.count { it.isForecast })
        assertEquals(anchored.last().dateOfSleep.plusDays(3), nonGapDays.last().date)
    }
}
