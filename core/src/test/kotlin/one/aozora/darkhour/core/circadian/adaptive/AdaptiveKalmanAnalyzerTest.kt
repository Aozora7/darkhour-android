package one.aozora.darkhour.core.circadian.adaptive

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveKalmanAnalyzerTest {
    @Test
    fun producesSmoothAppCompatibleOutputAndForecasts() {
        val analysis = analyzeCircadianAdaptiveKalman(
            records = generateSyntheticRecords(SyntheticOptions(days = 120, tau = 24.7, noise = 0.5)),
            extraDays = 7,
        )

        assertEquals(ADAPTIVE_KALMAN_ALGORITHM_ID, analysis.algorithmId)
        assertEquals(7, analysis.days.count { it.isForecast })
        assertTrue(abs(analysis.globalTau - 24.7) < 0.15)
        assertTrue(analysis.days.all { it.localTau.isFinite() && it.nightStartHour.isFinite() })
        val observedTau = analysis.days.filterNot { it.isForecast }.map { it.localTau }
        assertTrue(observedTau.max() - observedTau.min() < 0.4)
    }
}
