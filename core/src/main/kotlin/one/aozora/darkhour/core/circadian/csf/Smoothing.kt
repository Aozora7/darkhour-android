package one.aozora.darkhour.core.circadian.csf

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val MIN_EDGE_ANCHORS = 3

private data class UnwrappedHour(val dayNumber: Int, val clockHour: Double, val weight: Double)

private data class RegressionResult(
    val slope: Double,
    val intercept: Double,
    val unwrappedHours: List<UnwrappedHour>,
)

private fun buildUnwrappedHours(anchors: List<CsfAnchor>, dayStart: Int, dayEnd: Int): RegressionResult? {
    val selected = anchors.filter { it.dayNumber in dayStart..dayEnd }
    if (selected.size < 3) return null

    val pivotIndex = selected.indices.maxBy { selected[it].weight }
    val pivot = selected[pivotIndex]
    val unwrappedHours = mutableListOf<UnwrappedHour>()

    val pivotClockHour = normalizeAngle(pivot.midpointHour)
    unwrappedHours += UnwrappedHour(pivot.dayNumber, pivotClockHour, pivot.weight)

    var prevHour = pivotClockHour
    for (i in pivotIndex - 1 downTo 0) {
        val anchor = selected[i]
        var h = normalizeAngle(anchor.midpointHour)
        while (h - prevHour > 12.0) h -= 24.0
        while (prevHour - h > 12.0) h += 24.0
        unwrappedHours.add(0, UnwrappedHour(anchor.dayNumber, h, anchor.weight))
        prevHour = h
    }

    prevHour = pivotClockHour
    for (i in pivotIndex + 1 until selected.size) {
        val anchor = selected[i]
        var h = normalizeAngle(anchor.midpointHour)
        while (h - prevHour > 12.0) h -= 24.0
        while (prevHour - h > 12.0) h += 24.0
        unwrappedHours += UnwrappedHour(anchor.dayNumber, h, anchor.weight)
        prevHour = h
    }

    var sumW = 0.0
    var sumWx = 0.0
    var sumWy = 0.0
    var sumWxx = 0.0
    var sumWxy = 0.0
    for (anchor in unwrappedHours) {
        val w = anchor.weight
        sumW += w
        sumWx += w * anchor.dayNumber
        sumWy += w * anchor.clockHour
        sumWxx += w * anchor.dayNumber * anchor.dayNumber
        sumWxy += w * anchor.dayNumber * anchor.clockHour
    }

    val denom = sumW * sumWxx - sumWx * sumWx
    if (abs(denom) < 1e-10) return null

    val slope = (sumW * sumWxy - sumWx * sumWy) / denom
    val intercept = (sumWy - slope * sumWx) / sumW
    return RegressionResult(slope, intercept, unwrappedHours)
}

private fun correctEndEdge(
    states: MutableList<SmoothedState>,
    anchors: List<CsfAnchor>,
    segFirstDay: Int,
    lastDataLocalDay: Int,
    totalDays: Int,
    smoothing: CsfSmoothingConfig,
) {
    val lastDataDay = segFirstDay + lastDataLocalDay
    val eligibleAnchors = anchors.filter { it.dayNumber <= lastDataDay }
    if (eligibleAnchors.size < MIN_EDGE_ANCHORS) return

    val preferredStartDay = lastDataDay - smoothing.edgeRegressionLookbackDays
    val minimumAnchorStartDay = eligibleAnchors[eligibleAnchors.size - MIN_EDGE_ANCHORS].dayNumber
    val regression = buildUnwrappedHours(
        anchors = anchors,
        dayStart = min(preferredStartDay, minimumAnchorStartDay),
        dayEnd = lastDataDay,
    ) ?: return
    val edgeStart = max(0, lastDataLocalDay - smoothing.edgeBlendDays)
    val edgeEnd = min(lastDataLocalDay, totalDays)
    val regressionTau = (24.0 + regression.slope).coerceIn(TAU_MIN, TAU_MAX)

    for (localD in edgeStart..edgeEnd) {
        val state = states.getOrNull(localD) ?: continue
        val globalD = segFirstDay + localD

        var wResSum = 0.0
        var wSum = 0.0
        for (anchor in regression.unwrappedHours) {
            val dist = abs(anchor.dayNumber - globalD)
            if (dist > smoothing.edgeAnchorRadiusDays) continue
            val scaledDistance = dist / smoothing.edgeAnchorSigmaDays
            val gw = exp(-0.5 * scaledDistance * scaledDistance)
            val w = gw * anchor.weight
            val trendAtAnchor = regression.slope * anchor.dayNumber + regression.intercept
            wResSum += w * (anchor.clockHour - trendAtAnchor)
            wSum += w
        }

        val phaseCorrection = if (wSum >= 0.5) {
            val targetClockHour = regression.slope * globalD + regression.intercept + wResSum / wSum
            val currentClockHour = normalizeAngle(state.smoothedPhase)
            var correction = targetClockHour - currentClockHour
            while (correction > 12.0) correction -= 24.0
            while (correction < -12.0) correction += 24.0
            correction
        } else {
            0.0
        }

        val t = (localD - edgeStart).toDouble() / smoothing.edgeBlendDays
        val blendWeight = t * t
        states[localD] = state.withSmoothed(
            smoothedPhase = state.smoothedPhase + blendWeight * phaseCorrection,
            smoothedTau = state.smoothedTau + blendWeight * (regressionTau - state.smoothedTau),
        )
    }

    if (lastDataLocalDay < totalDays) {
        val lastDataState = states.getOrNull(lastDataLocalDay) ?: return
        val lastDataClockHour = normalizeAngle(lastDataState.smoothedPhase)

        for (localD in lastDataLocalDay + 1..totalDays) {
            val state = states.getOrNull(localD) ?: continue
            val dist = localD - lastDataLocalDay
            val forecastClockHour = lastDataClockHour + regression.slope * dist
            val currentClockHour = normalizeAngle(state.smoothedPhase)
            var correction = forecastClockHour - currentClockHour
            while (correction > 12.0) correction -= 24.0
            while (correction < -12.0) correction += 24.0

            states[localD] = state.withSmoothed(
                smoothedPhase = state.smoothedPhase + correction,
                smoothedTau = regressionTau,
            )
        }
    }
}

private fun correctStartEdgeFromInterior(
    states: MutableList<SmoothedState>,
    smoothing: CsfSmoothingConfig,
) {
    val refStart = smoothing.startReferenceFirstDay
    val refEnd = smoothing.startReferenceLastDay
    if (states.size <= refEnd) return

    val refPoints = (refStart..refEnd)
        .filter { it < states.size }
        .map { it to states[it].smoothedPhase }
    if (refPoints.size < 5) return

    var n = 0
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    for ((localD, phase) in refPoints) {
        n++
        sumX += localD
        sumY += phase
        sumXX += localD * localD
        sumXY += localD * phase
    }

    val denom = n * sumXX - sumX * sumX
    if (abs(denom) < 1e-10) return

    val slope = (n * sumXY - sumX * sumY) / denom
    val intercept = (sumY - slope * sumX) / n

    for (localD in 0 until min(smoothing.edgeBlendDays, states.size)) {
        val state = states[localD]
        val targetPhase = slope * localD + intercept
        val correction = (targetPhase - state.smoothedPhase).coerceIn(-6.0, 6.0)
        val t = 1.0 - localD.toDouble() / smoothing.edgeBlendDays
        states[localD] = state.withSmoothed(smoothedPhase = state.smoothedPhase + t * t * correction)
    }
}

fun correctEdges(
    states: MutableList<SmoothedState>,
    anchors: List<CsfAnchor>,
    segFirstDay: Int,
    lastDataLocalDay: Int,
    totalDays: Int,
    smoothing: CsfSmoothingConfig,
) {
    if (anchors.size < MIN_EDGE_ANCHORS) return
    if (lastDataLocalDay < smoothing.edgeBlendDays) return

    correctStartEdgeFromInterior(states, smoothing)
    correctEndEdge(states, anchors, segFirstDay, lastDataLocalDay, totalDays, smoothing)
}

fun smoothOutputStates(
    states: List<SmoothedState>,
    smoothing: CsfSmoothingConfig,
): List<SmoothedState> {
    if (states.size < 3) return states

    val smoothed = states.toMutableList()
    for (i in states.indices) {
        var phaseSum = 0.0
        var tauSum = 0.0
        var weightSum = 0.0

        for (j in max(0, i - smoothing.outputRadiusDays)..min(states.lastIndex, i + smoothing.outputRadiusDays)) {
            val dist = abs(j - i)
            val scaledDistance = dist / smoothing.smoothingDays
            val weight = exp(-0.5 * scaledDistance * scaledDistance)
            phaseSum += weight * states[j].smoothedPhase
            tauSum += weight * states[j].smoothedTau
            weightSum += weight
        }

        if (weightSum > 0.0) {
            smoothed[i] = smoothed[i].withSmoothed(
                smoothedPhase = phaseSum / weightSum,
                smoothedTau = tauSum / weightSum,
            )
        }
    }

    return smoothed
}
