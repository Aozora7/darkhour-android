package one.aozora.darkhour.core.circadian.groundtruth

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private const val TRANSITION_SLOPE_WINDOW_DAYS = 14L
private const val TRANSITION_SCORING_DAYS = 14L
private const val MIN_TRANSITION_SLOPE_DAYS = 5
private const val SIGNIFICANT_SLOPE_DELTA_HOURS_PER_DAY = 0.30

data class GroundTruthRegimeTransitionScore(
    val transitions: Int,
    val scoredDays: Int,
    val meanAbsoluteMovementErrorHours: Double,
    val p90AbsoluteMovementErrorHours: Double,
)

/**
 * Scores how quickly an estimator follows labeled changes in tau. Control
 * points only nominate possible boundaries; slopes are fitted to the manual
 * daily overlay on either side so closely spaced editor points do not become
 * false regime changes. Absolute phase offset is deliberately removed at the
 * boundary because it is already covered by [scoreAgainstGroundTruth].
 */
fun scoreRegimeTransitions(
    prediction: List<GroundTruthPredictionDay>,
    dataset: GroundTruthDataset,
): GroundTruthRegimeTransitionScore {
    val expected = dataset.overlay.sortedBy { it.date }
    val usablePrediction = prediction.filterNot { it.isForecast || it.isGap }.sortedBy { it.date }
    val transitionDates = significantTransitionDates(dataset.controlPoints, expected)
    val expectedUnwrapped = expected.associate { it.date to midpoint(it) }
    val predictedUnwrapped = unwrapPrediction(usablePrediction)
    val errors = transitionDates.flatMap { boundary ->
        val baseline = boundary.minusDays(1)
        val expectedBaseline = expectedUnwrapped[baseline] ?: return@flatMap emptyList()
        val predictedBaseline = predictedUnwrapped[baseline] ?: return@flatMap emptyList()
        (0 until TRANSITION_SCORING_DAYS).mapNotNull { offset ->
            val date = boundary.plusDays(offset)
            val expectedValue = expectedUnwrapped[date] ?: return@mapNotNull null
            val predictedValue = predictedUnwrapped[date] ?: return@mapNotNull null
            abs((predictedValue - predictedBaseline) - (expectedValue - expectedBaseline))
        }
    }.sorted()
    return GroundTruthRegimeTransitionScore(
        transitions = transitionDates.size,
        scoredDays = errors.size,
        meanAbsoluteMovementErrorHours = errors.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
        p90AbsoluteMovementErrorHours = errors.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.9) } ?: 0.0,
    )
}

private data class TransitionCandidate(val date: LocalDate, val slopeDelta: Double)

private fun significantTransitionDates(
    controlPoints: List<GroundTruthControlPoint>,
    overlay: List<GroundTruthOverlayDay>,
): List<LocalDate> {
    val candidates = controlPoints.distinctBy { it.date }.mapNotNull { point ->
        val before = overlay.filter { it.date >= point.date.minusDays(TRANSITION_SLOPE_WINDOW_DAYS) && it.date < point.date }
        val after = overlay.filter { it.date >= point.date && it.date < point.date.plusDays(TRANSITION_SLOPE_WINDOW_DAYS) }
        if (before.size < MIN_TRANSITION_SLOPE_DAYS || after.size < MIN_TRANSITION_SLOPE_DAYS) return@mapNotNull null
        val delta = abs(linearSlope(before) - linearSlope(after))
        if (delta >= SIGNIFICANT_SLOPE_DELTA_HOURS_PER_DAY) TransitionCandidate(point.date, delta) else null
    }
    // One physical transition can have several nearby editor control points.
    // Keep the strongest candidate in each 14-day neighborhood.
    val selected = mutableListOf<TransitionCandidate>()
    for (candidate in candidates.sortedByDescending { it.slopeDelta }) {
        if (selected.none { abs(ChronoUnit.DAYS.between(it.date, candidate.date)) < TRANSITION_SLOPE_WINDOW_DAYS }) {
            selected += candidate
        }
    }
    return selected.map { it.date }.sorted()
}

private fun linearSlope(days: List<GroundTruthOverlayDay>): Double {
    val origin = days.first().date
    val x = days.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
    val y = days.map(::midpoint)
    val meanX = x.average()
    val meanY = y.average()
    val denominator = x.sumOf { (it - meanX) * (it - meanX) }
    return if (denominator == 0.0) 0.0 else x.indices.sumOf { (x[it] - meanX) * (y[it] - meanY) } / denominator
}

private fun unwrapPrediction(days: List<GroundTruthPredictionDay>): Map<LocalDate, Double> {
    if (days.isEmpty()) return emptyMap()
    val result = mutableMapOf(days.first().date to midpoint(days.first()))
    var previousCircular = midpoint(days.first())
    var unwrapped = previousCircular
    days.drop(1).forEach { day ->
        val current = midpoint(day)
        var step = current - previousCircular
        while (step > 12.0) step -= 24.0
        while (step < -12.0) step += 24.0
        unwrapped += step
        result[day.date] = unwrapped
        previousCircular = current
    }
    return result
}

private fun midpoint(day: GroundTruthPredictionDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun midpoint(day: GroundTruthOverlayDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun percentile(sorted: List<Double>, percentile: Double): Double =
    sorted[(sorted.size * percentile).toInt().coerceAtMost(sorted.lastIndex)]
