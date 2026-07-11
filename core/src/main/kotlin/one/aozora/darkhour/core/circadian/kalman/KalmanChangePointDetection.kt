package one.aozora.darkhour.core.circadian.kalman

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class DetectedKalmanChangePoint(
    val dayNumber: Int,
    val previousDrift: Double,
    val newDrift: Double,
    val evidenceRatio: Double,
    val confirmationDayNumber: Int,
)

/**
 * Detects persistent slope changes from causal filtered prefixes. RTS output is
 * deliberately unavailable here, preventing future observations from moving
 * either a candidate boundary or its continuation hypothesis. Automatic hard
 * splits are intentionally limited to transitions between near-entrained and
 * plausible free-running drift; arbitrary short-window tau changes remain
 * ordinary Kalman process variation because sleep alone cannot identify them
 * reliably enough to rewrite regime history.
 */
internal fun detectKalmanChangePoints(
    observations: List<KalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: KalmanConfig,
): List<DetectedKalmanChangePoint> {
    if (observations.isEmpty()) return emptyList()
    val detection = config.changeDetection
    val changes = mutableListOf<DetectedKalmanChangePoint>()
    val trace = runKalmanForward(observations, firstDay, lastDay, config)
    val byDay = trace.associateBy(FilterStep::dayNumber)
    var regimeStart = firstDay
    var confirmationDay = firstDay + 2 * detection.windowDays

    while (confirmationDay <= lastDay) {
        val eligible = observations.filter {
            it.dayNumber in regimeStart..confirmationDay && it.weight >= detection.minAnchorWeight
        }
        val earliestCandidate = max(
            regimeStart + 2 * detection.windowDays,
            confirmationDay - detection.windowDays + 1,
        )
        val candidates = eligible.asSequence()
            .map(KalmanObservation::dayNumber)
            .filter { it in earliestCandidate..confirmationDay }
            .distinct()
            .mapNotNull { boundary ->
                evaluateCandidate(boundary, confirmationDay, eligible, byDay, config)
            }
            .toList()
        val confirmed = candidates.minByOrNull(DetectedKalmanChangePoint::dayNumber)
        if (confirmed == null) {
            confirmationDay++
        } else {
            changes += confirmed
            regimeStart = confirmed.dayNumber
            confirmationDay = max(confirmationDay + 1, regimeStart + 2 * detection.windowDays)
        }
    }
    return changes
}

private fun evaluateCandidate(
    boundary: Int,
    confirmationDay: Int,
    eligible: List<KalmanObservation>,
    traceByDay: Map<Int, FilterStep>,
    config: KalmanConfig,
): DetectedKalmanChangePoint? {
    val detection = config.changeDetection
    val evidence = eligible.filter { it.dayNumber in boundary..confirmationDay }
    if (evidence.size < detection.minAnchors) return null
    val boundaryPrediction = traceByDay[boundary]?.predicted ?: return null
    val samples = evidence.mapNotNull { observation ->
        val resolved = traceByDay[observation.dayNumber]?.resolvedObservation ?: return@mapNotNull null
        RobustSlopeSample(
            dayOffset = (observation.dayNumber - boundary).toDouble(),
            phaseOffset = resolved - boundaryPrediction.phase,
            weight = observation.weight,
        )
    }
    if (samples.size < detection.minAnchors || samples.all { it.dayOffset == 0.0 }) return null
    val newDrift = fitHuberSlope(samples, config.measurementVarianceAtUnitWeight)
    if (abs(newDrift - boundaryPrediction.drift) < detection.minDriftDelta) return null
    if (!isEntrainmentBoundary(boundaryPrediction.drift, newDrift)) return null
    val oldLoss = huberLoss(samples, boundaryPrediction.drift, config.measurementVarianceAtUnitWeight)
    val newLoss = huberLoss(samples, newDrift, config.measurementVarianceAtUnitWeight)
    val halves = samples.chunked((samples.size + 1) / 2)
    val halfSlopes = halves.map { fitHuberLineSlope(it, config.measurementVarianceAtUnitWeight) }
    val expectedDirection = newDrift - boundaryPrediction.drift
    if (halfSlopes.any { halfSlope ->
            val halfDelta = halfSlope - boundaryPrediction.drift
            abs(halfDelta) < detection.minDriftDelta || halfDelta * expectedDirection <= 0.0
        }
    ) return null
    val minimumHalfRatio = detection.fitImprovement
    if (halves.any { half ->
            val halfOldLoss = huberLoss(half, boundaryPrediction.drift, config.measurementVarianceAtUnitWeight)
            val halfNewLoss = huberLoss(half, newDrift, config.measurementVarianceAtUnitWeight)
            halfOldLoss / max(halfNewLoss, 1e-9) < minimumHalfRatio
        }
    ) return null
    val ratio = oldLoss / max(newLoss, 1e-9)
    if (!ratio.isFinite() || ratio < detection.fitImprovement) return null
    // A ratio alone accepts a new line that is merely less bad than the old
    // one. Requiring a close absolute fit is the main defense against alarm
    // caps and other short, schedule-constrained runs. Residuals are already
    // dimensionless here because Huber loss uses Kalman's measurement model.
    if (newLoss / samples.size > MAX_MEAN_STANDARDIZED_HUBER_LOSS) return null
    return DetectedKalmanChangePoint(
        dayNumber = boundary,
        previousDrift = boundaryPrediction.drift,
        newDrift = newDrift,
        evidenceRatio = ratio,
        confirmationDayNumber = confirmationDay,
    )
}

private data class RobustSlopeSample(
    val dayOffset: Double,
    val phaseOffset: Double,
    val weight: Double,
)

private fun fitHuberSlope(samples: List<RobustSlopeSample>, measurementVariance: Double): Double {
    var slope = weightedSlope(samples) { it.weight / measurementVariance }
    repeat(12) {
        val next = weightedSlope(samples) { sample ->
            val sigma = sqrt(measurementVariance / sample.weight)
            val standardized = (sample.phaseOffset - slope * sample.dayOffset) / sigma
            val robustWeight = if (abs(standardized) <= HUBER_K) 1.0 else HUBER_K / abs(standardized)
            robustWeight * sample.weight / measurementVariance
        }
        if (abs(next - slope) < 1e-7) return next
        slope = next
    }
    return slope
}

private fun fitHuberLineSlope(samples: List<RobustSlopeSample>, measurementVariance: Double): Double {
    var line = weightedLine(samples) { it.weight / measurementVariance }
    repeat(12) {
        val current = line
        val next = weightedLine(samples) { sample ->
            val sigma = sqrt(measurementVariance / sample.weight)
            val standardized = (sample.phaseOffset - current.first - current.second * sample.dayOffset) / sigma
            val robustWeight = if (abs(standardized) <= HUBER_K) 1.0 else HUBER_K / abs(standardized)
            robustWeight * sample.weight / measurementVariance
        }
        if (abs(next.first - line.first) + abs(next.second - line.second) < 1e-7) return next.second
        line = next
    }
    return line.second
}

private inline fun weightedLine(
    samples: List<RobustSlopeSample>,
    weight: (RobustSlopeSample) -> Double,
): Pair<Double, Double> {
    var sumWeight = 0.0
    var sumX = 0.0
    var sumY = 0.0
    for (sample in samples) {
        val w = weight(sample)
        sumWeight += w
        sumX += w * sample.dayOffset
        sumY += w * sample.phaseOffset
    }
    if (sumWeight <= 1e-12) return 0.0 to 0.0
    val meanX = sumX / sumWeight
    val meanY = sumY / sumWeight
    var numerator = 0.0
    var denominator = 0.0
    for (sample in samples) {
        val w = weight(sample)
        numerator += w * (sample.dayOffset - meanX) * (sample.phaseOffset - meanY)
        denominator += w * (sample.dayOffset - meanX) * (sample.dayOffset - meanX)
    }
    val slope = if (denominator > 1e-12) numerator / denominator else 0.0
    return (meanY - slope * meanX) to slope
}

private inline fun weightedSlope(
    samples: List<RobustSlopeSample>,
    weight: (RobustSlopeSample) -> Double,
): Double {
    var numerator = 0.0
    var denominator = 0.0
    for (sample in samples) {
        val w = weight(sample)
        numerator += w * sample.dayOffset * sample.phaseOffset
        denominator += w * sample.dayOffset * sample.dayOffset
    }
    return if (denominator > 1e-12) numerator / denominator else 0.0
}

private fun huberLoss(
    samples: List<RobustSlopeSample>,
    slope: Double,
    measurementVariance: Double,
): Double = samples.sumOf { sample ->
    val sigma = sqrt(measurementVariance / sample.weight)
    val residual = abs((sample.phaseOffset - slope * sample.dayOffset) / sigma)
    if (residual <= HUBER_K) 0.5 * residual * residual
    else HUBER_K * (residual - 0.5 * HUBER_K)
}

private const val HUBER_K = 1.345
private const val MAX_MEAN_STANDARDIZED_HUBER_LOSS = 0.008
private const val MAX_ENTRAINED_DRIFT = 0.25
private const val MIN_FREE_RUNNING_DRIFT = 0.50
private const val MAX_FREE_RUNNING_DRIFT = 1.50

private fun isEntrainmentBoundary(previousDrift: Double, newDrift: Double): Boolean {
    fun isEntrained(drift: Double) = abs(drift) <= MAX_ENTRAINED_DRIFT
    fun isPlausiblyFreeRunning(drift: Double) = abs(drift) in MIN_FREE_RUNNING_DRIFT..MAX_FREE_RUNNING_DRIFT
    return (isEntrained(previousDrift) && isPlausiblyFreeRunning(newDrift)) ||
        (isEntrained(newDrift) && isPlausiblyFreeRunning(previousDrift))
}
