package one.aozora.darkhour.core.circadian

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayConfidenceNormalizationTest {
    @Test
    fun scalesDifferentAlgorithmConfidenceUnitsToTheSameRelativeOpacity() {
        val first = analysisOf(0.2, 0.4, 0.6).withNormalizedOverlayConfidence()
        val second = analysisOf(0.5, 1.0, 1.5).withNormalizedOverlayConfidence()

        first.days.zip(second.days).forEach { (a, b) ->
            assertEquals(a.overlayConfidence, b.overlayConfidence, 1e-9)
        }
        assertEquals(0.60, first.days.map(CircadianDay::overlayConfidence).average(), 1e-9)
        assertEquals(0.4, first.days[1].confidenceScore, 0.0)
    }

    @Test
    fun excludesForecastsAndGapsFromTheNormalizationBaseline() {
        val analysis = analysisOf(0.2, 0.4).copyWithDays(
            analysisOf(0.2, 0.4).days + day(score = 0.15, forecast = true) + day(score = 0.8, gap = true),
        ).withNormalizedOverlayConfidence()

        assertEquals(0.4, analysis.days[0].overlayConfidence, 1e-9)
        assertEquals(0.8, analysis.days[1].overlayConfidence, 1e-9)
        assertEquals(0.3, analysis.days[2].overlayConfidence, 1e-9)
        assertEquals(0.0, analysis.days[3].overlayConfidence, 0.0)
    }

    @Test
    fun leavesNoUsableBaselineUntouched() {
        val analysis = analysisOf(0.0, 0.0)

        assertTrue(analysis.withNormalizedOverlayConfidence() === analysis)
    }

    private fun analysisOf(vararg scores: Double): TestAnalysis = TestAnalysis(
        days = scores.mapIndexed { index, score -> day(score, index.toLong()) },
    )

    private fun day(score: Double, offset: Long = 0, forecast: Boolean = false, gap: Boolean = false) = CircadianDay(
        date = LocalDate.parse("2026-01-01").plusDays(offset),
        nightStartHour = 1.0,
        nightEndHour = 9.0,
        confidenceScore = score,
        confidence = CircadianConfidence.MEDIUM,
        localTau = 24.0,
        localDrift = 0.0,
        isForecast = forecast,
        isGap = gap,
    )

    private data class TestAnalysis(
        override val days: List<CircadianDay>,
    ) : CircadianAnalysis {
        override val globalTau = 24.0
        override val globalDailyDrift = 0.0
        override val algorithmId = "test"
        override val tau = 24.0
        override val dailyDrift = 0.0
        override val rSquared = 0.0

        fun copyWithDays(days: List<CircadianDay>) = copy(days = days)
    }
}
