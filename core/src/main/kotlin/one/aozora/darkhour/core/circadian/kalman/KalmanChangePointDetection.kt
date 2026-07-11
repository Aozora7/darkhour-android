package one.aozora.darkhour.core.circadian.kalman

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class DetectedKalmanChangePoint(
    val dayNumber: Int,
    val previousDrift: Double,
    val newDrift: Double,
    val observationOffset: Double,
    val evidenceRatio: Double,
    val confirmationDayNumber: Int,
)

/** Optional process-wide sink used by debug builds to inspect detector decisions. */
object KalmanChangeDetectionDiagnostics {
    @Volatile
    var logger: ((String) -> Unit)? = null

    internal fun log(message: String) {
        logger?.invoke(message)
    }
}

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

    KalmanChangeDetectionDiagnostics.log(
        "scan firstDay=$firstDay lastDay=$lastDay observations=${observations.size} " +
            "window=${detection.windowDays} minAnchors=${detection.minAnchors} " +
            "minWeight=${detection.minAnchorWeight} minDelta=${detection.minDriftDelta} " +
            "minRatio=${detection.fitImprovement}",
    )

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
                evaluateCandidate(
                    boundary,
                    confirmationDay,
                    eligible,
                    byDay,
                    config,
                    logDecision = confirmationDay == lastDay,
                )
            }
            .toList()
        val phaseContinuousCandidates = candidates.filter { abs(it.observationOffset) < 1e-9 }
        val confirmed = (phaseContinuousCandidates.ifEmpty { candidates })
            .minByOrNull(DetectedKalmanChangePoint::dayNumber)
        if (confirmed == null) {
            confirmationDay++
        } else {
            KalmanChangeDetectionDiagnostics.log(
                "confirmed boundary=${confirmed.dayNumber} confirmation=${confirmed.confirmationDayNumber} " +
                    "oldDrift=${confirmed.previousDrift} newDrift=${confirmed.newDrift} " +
                    "offset=${confirmed.observationOffset} ratio=${confirmed.evidenceRatio}",
            )
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
    logDecision: Boolean,
): DetectedKalmanChangePoint? {
    val detection = config.changeDetection
    val evidence = eligible.filter { it.dayNumber in boundary..confirmationDay }
    fun reject(reason: String): DetectedKalmanChangePoint? {
        if (logDecision) {
            KalmanChangeDetectionDiagnostics.log(
                "rejected boundary=$boundary confirmation=$confirmationDay evidence=${evidence.size} reason=$reason",
            )
        }
        return null
    }
    if (evidence.size < detection.minAnchors) {
        return reject("anchors ${evidence.size} < ${detection.minAnchors}")
    }
    val boundaryPrediction = traceByDay[boundary]?.predicted
        ?: return reject("missing boundary prediction")
    val samples = evidence.mapNotNull { observation ->
        val resolved = traceByDay[observation.dayNumber]?.resolvedObservation ?: return@mapNotNull null
        RobustSlopeSample(
            dayOffset = (observation.dayNumber - boundary).toDouble(),
            phaseOffset = resolved - boundaryPrediction.phase,
            weight = observation.weight,
        )
    }
    if (samples.size < detection.minAnchors) return reject("resolved samples ${samples.size} < ${detection.minAnchors}")
    if (samples.all { it.dayOffset == 0.0 }) return reject("samples have no time span")
    val offsetFit = fitHuberOffsetSlope(samples, config.measurementVarianceAtUnitWeight)
    val continuousSlope = fitHuberFixedOffsetSlope(samples, config.measurementVarianceAtUnitWeight, offset = 0.0)
    val continuousLoss = huberLoss(samples, continuousSlope, config.measurementVarianceAtUnitWeight)
    val continuousFitIsClose = continuousLoss / samples.size <= MAX_MEAN_STANDARDIZED_HUBER_LOSS
    val continuousFitIsEntrainment =
        abs(continuousSlope - boundaryPrediction.drift) >= detection.minDriftDelta &&
            isEntrainmentBoundary(boundaryPrediction.drift, continuousSlope)
    val newFit = if (continuousFitIsClose && continuousFitIsEntrainment) {
        RobustOffsetSlopeFit(offset = 0.0, slope = continuousSlope)
    } else {
        offsetFit
    }
    val newDrift = newFit.slope
    val driftDelta = abs(newDrift - boundaryPrediction.drift)
    if (driftDelta < detection.minDriftDelta) {
        return reject("drift delta=$driftDelta old=${boundaryPrediction.drift} new=$newDrift")
    }
    if (!isEntrainmentBoundary(boundaryPrediction.drift, newDrift)) {
        return reject("not entrainment boundary old=${boundaryPrediction.drift} new=$newDrift")
    }
    val oldLoss = huberLoss(samples, boundaryPrediction.drift, config.measurementVarianceAtUnitWeight)
    val newLoss = huberLoss(samples, newDrift, config.measurementVarianceAtUnitWeight, newFit.offset)
    val halves = samples.chunked((samples.size + 1) / 2)
    val halfSlopes = halves.map { fitHuberLineSlope(it, config.measurementVarianceAtUnitWeight) }
    val expectedDirection = newDrift - boundaryPrediction.drift
    if (halfSlopes.any { halfSlope ->
            val halfDelta = halfSlope - boundaryPrediction.drift
            abs(halfDelta) < detection.minDriftDelta || halfDelta * expectedDirection <= 0.0
        }
    ) return reject("inconsistent half slopes=$halfSlopes old=${boundaryPrediction.drift} new=$newDrift")
    val minimumHalfRatio = detection.fitImprovement
    val halfRatios = halves.map { half ->
            val halfOldLoss = huberLoss(half, boundaryPrediction.drift, config.measurementVarianceAtUnitWeight)
            val halfNewLoss = huberLoss(half, newDrift, config.measurementVarianceAtUnitWeight, newFit.offset)
            halfOldLoss / max(halfNewLoss, 1e-9)
        }
    if (halfRatios.any { it < minimumHalfRatio }) {
        return reject("half fit ratios=$halfRatios < $minimumHalfRatio")
    }
    val ratio = oldLoss / max(newLoss, 1e-9)
    if (!ratio.isFinite() || ratio < detection.fitImprovement) {
        return reject("fit ratio=$ratio < ${detection.fitImprovement} oldLoss=$oldLoss newLoss=$newLoss")
    }
    // A ratio alone accepts a new line that is merely less bad than the old
    // one. Requiring a close absolute fit is the main defense against alarm
    // caps and other short, schedule-constrained runs. Residuals are already
    // dimensionless here because Huber loss uses Kalman's measurement model.
    val meanNewLoss = newLoss / samples.size
    if (meanNewLoss > MAX_MEAN_STANDARDIZED_HUBER_LOSS) {
        return reject(
            "mean standardized Huber loss=$meanNewLoss > $MAX_MEAN_STANDARDIZED_HUBER_LOSS " +
                "ratio=$ratio offset=${newFit.offset} old=${boundaryPrediction.drift} new=$newDrift",
        )
    }
    if (logDecision) {
        KalmanChangeDetectionDiagnostics.log(
            "accepted boundary=$boundary confirmation=$confirmationDay evidence=${evidence.size} " +
                "oldDrift=${boundaryPrediction.drift} newDrift=$newDrift offset=${newFit.offset} " +
                "ratio=$ratio meanLoss=$meanNewLoss",
        )
    }
    return DetectedKalmanChangePoint(
        dayNumber = boundary,
        previousDrift = boundaryPrediction.drift,
        newDrift = newDrift,
        observationOffset = newFit.offset,
        evidenceRatio = ratio,
        confirmationDayNumber = confirmationDay,
    )
}

private data class RobustSlopeSample(
    val dayOffset: Double,
    val phaseOffset: Double,
    val weight: Double,
)

private data class RobustOffsetSlopeFit(val offset: Double, val slope: Double)

private fun fitHuberFixedOffsetSlope(
    samples: List<RobustSlopeSample>,
    measurementVariance: Double,
    offset: Double,
): Double {
    var slope = fixedOffsetSlope(samples, offset) { it.weight / measurementVariance }
    repeat(12) {
        val current = slope
        val next = fixedOffsetSlope(samples, offset) { sample ->
            val sigma = sqrt(measurementVariance / sample.weight)
            val standardized = (sample.phaseOffset - offset - current * sample.dayOffset) / sigma
            val robustWeight = if (abs(standardized) <= HUBER_K) 1.0 else HUBER_K / abs(standardized)
            robustWeight * sample.weight / measurementVariance
        }
        if (abs(next - slope) < 1e-7) return next
        slope = next
    }
    return slope
}

private inline fun fixedOffsetSlope(
    samples: List<RobustSlopeSample>,
    offset: Double,
    weight: (RobustSlopeSample) -> Double,
): Double {
    var numerator = 0.0
    var denominator = 0.0
    for (sample in samples) {
        val w = weight(sample)
        numerator += w * sample.dayOffset * (sample.phaseOffset - offset)
        denominator += w * sample.dayOffset * sample.dayOffset
    }
    return if (denominator > 1e-12) numerator / denominator else 0.0
}

/**
 * Fits sleep timing relative to the continuous circadian phase. The bounded
 * offset is evidence-only: it lets a stable change in sleep placement avoid
 * masquerading as reverse drift, but is never applied to the regime state.
 */
private fun fitHuberOffsetSlope(
    samples: List<RobustSlopeSample>,
    measurementVariance: Double,
): RobustOffsetSlopeFit {
    var fit = weightedOffsetSlope(samples) { it.weight / measurementVariance }
    repeat(12) {
        val current = fit
        val next = weightedOffsetSlope(samples) { sample ->
            val sigma = sqrt(measurementVariance / sample.weight)
            val standardized =
                (sample.phaseOffset - current.offset - current.slope * sample.dayOffset) / sigma
            val robustWeight = if (abs(standardized) <= HUBER_K) 1.0 else HUBER_K / abs(standardized)
            robustWeight * sample.weight / measurementVariance
        }
        if (abs(next.offset - fit.offset) + abs(next.slope - fit.slope) < 1e-7) return next
        fit = next
    }
    return fit
}

private inline fun weightedOffsetSlope(
    samples: List<RobustSlopeSample>,
    weight: (RobustSlopeSample) -> Double,
): RobustOffsetSlopeFit {
    val unconstrained = weightedLine(samples, weight)
    val offset = unconstrained.first.coerceIn(-MAX_OBSERVATION_OFFSET_HOURS, MAX_OBSERVATION_OFFSET_HOURS)
    var numerator = 0.0
    var denominator = 0.0
    for (sample in samples) {
        val w = weight(sample)
        numerator += w * sample.dayOffset * (sample.phaseOffset - offset)
        denominator += w * sample.dayOffset * sample.dayOffset
    }
    val slope = if (denominator > 1e-12) numerator / denominator else 0.0
    return RobustOffsetSlopeFit(offset, slope)
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

private fun huberLoss(
    samples: List<RobustSlopeSample>,
    slope: Double,
    measurementVariance: Double,
    offset: Double = 0.0,
): Double = samples.sumOf { sample ->
    val sigma = sqrt(measurementVariance / sample.weight)
    val residual = abs((sample.phaseOffset - offset - slope * sample.dayOffset) / sigma)
    if (residual <= HUBER_K) 0.5 * residual * residual
    else HUBER_K * (residual - 0.5 * HUBER_K)
}

private const val HUBER_K = 1.345
private const val MAX_MEAN_STANDARDIZED_HUBER_LOSS = 0.008
private const val MAX_OBSERVATION_OFFSET_HOURS = 6.0
private const val MAX_ENTRAINED_DRIFT = 0.25
private const val MIN_FREE_RUNNING_DRIFT = 0.50
private const val MAX_FREE_RUNNING_DRIFT = 1.50

private fun isEntrainmentBoundary(previousDrift: Double, newDrift: Double): Boolean {
    fun isEntrained(drift: Double) = abs(drift) <= MAX_ENTRAINED_DRIFT
    fun isPlausiblyFreeRunning(drift: Double) = abs(drift) in MIN_FREE_RUNNING_DRIFT..MAX_FREE_RUNNING_DRIFT
    return (isEntrained(previousDrift) && isPlausiblyFreeRunning(newDrift)) ||
        (isEntrained(newDrift) && isPlausiblyFreeRunning(previousDrift))
}
