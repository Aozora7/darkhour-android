package one.aozora.darkhour.core.circadian

private const val COMMON_MEAN_OVERLAY_CONFIDENCE = 0.60

/**
 * Gives each algorithm/data run the same mean overlay opacity while retaining
 * its relative day-to-day confidence variation. Algorithm-native confidence
 * remains available as [CircadianDay.confidenceScore].
 */
fun CircadianAnalysis.withNormalizedOverlayConfidence(): CircadianAnalysis {
    val observedScores = days.asSequence()
        .filter { !it.isGap && !it.isForecast }
        .map(CircadianDay::confidenceScore)
        .filter(Double::isFinite)
        .filter { it > 0.0 }
        .toList()
    val mean = observedScores.average().takeIf { it.isFinite() && it > 0.0 } ?: return this

    val normalizedDays = days.map { day ->
        val raw = day.confidenceScore.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        val overlayConfidence = if (day.isGap) 0.0 else {
            (raw / mean * COMMON_MEAN_OVERLAY_CONFIDENCE).coerceIn(0.0, 1.0)
        }
        day.copy(overlayConfidence = overlayConfidence)
    }
    return OverlayConfidenceNormalizedAnalysis(this, normalizedDays)
}

private data class OverlayConfidenceNormalizedAnalysis(
    private val source: CircadianAnalysis,
    override val days: List<CircadianDay>,
) : CircadianAnalysis by source
