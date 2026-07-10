package one.aozora.darkhour.core.circadian.groundtruth

import java.time.LocalDate
import kotlin.math.abs

/**
 * Measures sustained differences in phase velocity rather than circular phase
 * error. A one-hour daily drift mismatch remains visible after it accumulates
 * into a complete extra or missing phase cycle.
 */
data class GroundTruthTauDivergence(
    val windowDays: Int,
    val windows: Int,
    val meanAbsoluteDeltaMinutesPerDay: Double,
    val p90AbsoluteDeltaMinutesPerDay: Double,
    val maxAbsoluteDeltaMinutesPerDay: Double,
    val significantWindowFraction: Double,
)

fun scoreTauDivergence(
    prediction: List<GroundTruthPredictionDay>,
    dataset: GroundTruthDataset,
    windowDays: Int = 42,
    significantDeltaMinutesPerDay: Double = 30.0,
): GroundTruthTauDivergence {
    require(windowDays >= 2)
    require(significantDeltaMinutesPerDay >= 0.0)

    val expectedByDate = dataset.overlay.associateBy(GroundTruthOverlayDay::date)
    val actualByDate = prediction
        .filterNot { it.isForecast || it.isGap }
        .associateBy(GroundTruthPredictionDay::date)
    val dates = expectedByDate.keys.intersect(actualByDate.keys).sorted()
    val deltaByDate = dates.zipWithNext().mapNotNull { (previousDate, currentDate) ->
        if (currentDate != previousDate.plusDays(1)) return@mapNotNull null
        val expectedPrevious = expectedByDate.getValue(previousDate)
        val expectedCurrent = expectedByDate.getValue(currentDate)
        val actualPrevious = actualByDate.getValue(previousDate)
        val actualCurrent = actualByDate.getValue(currentDate)
        DailyDriftDelta(
            date = currentDate,
            hours = unwrappedStep(midpoint(actualCurrent), midpoint(actualPrevious)) -
                unwrappedStep(midpoint(expectedCurrent), midpoint(expectedPrevious)),
        )
    }

    val requiredSteps = windowDays - 1
    val windowDeltas = contiguousSegments(deltaByDate).flatMap { segment ->
        segment.windowed(requiredSteps, step = 1, partialWindows = false).map { window ->
            abs(window.sumOf(DailyDriftDelta::hours) / window.size) * 60.0
        }
    }.sorted()

    return GroundTruthTauDivergence(
        windowDays = windowDays,
        windows = windowDeltas.size,
        meanAbsoluteDeltaMinutesPerDay = windowDeltas.takeIf { it.isNotEmpty() }?.average() ?: Double.POSITIVE_INFINITY,
        p90AbsoluteDeltaMinutesPerDay = percentile(windowDeltas, 0.90),
        maxAbsoluteDeltaMinutesPerDay = windowDeltas.lastOrNull() ?: Double.POSITIVE_INFINITY,
        significantWindowFraction = windowDeltas.takeIf { it.isNotEmpty() }
            ?.count { it >= significantDeltaMinutesPerDay }
            ?.toDouble()
            ?.div(windowDeltas.size)
            ?: 1.0,
    )
}

private data class DailyDriftDelta(val date: LocalDate, val hours: Double)

private fun contiguousSegments(values: List<DailyDriftDelta>): List<List<DailyDriftDelta>> {
    if (values.isEmpty()) return emptyList()
    val result = mutableListOf<MutableList<DailyDriftDelta>>()
    values.forEach { value ->
        val current = result.lastOrNull()
        if (current == null || value.date != current.last().date.plusDays(1)) {
            result += mutableListOf(value)
        } else {
            current += value
        }
    }
    return result
}

private fun midpoint(day: GroundTruthPredictionDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun midpoint(day: GroundTruthOverlayDay) = (day.nightStartHour + day.nightEndHour) / 2.0

private fun unwrappedStep(current: Double, previous: Double): Double {
    var unwrapped = current
    while (unwrapped - previous > 12.0) unwrapped -= 24.0
    while (previous - unwrapped > 12.0) unwrapped += 24.0
    return unwrapped - previous
}

private fun percentile(sorted: List<Double>, fraction: Double): Double =
    sorted.getOrNull((sorted.size * fraction).toInt().coerceAtMost(sorted.lastIndex)) ?: Double.POSITIVE_INFINITY
