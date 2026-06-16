package one.aozora.darkhour.core.circadian.csf

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val EDGE_WINDOW = 10
private const val ANCHOR_HALF_WINDOW = 15
private const val ANCHOR_SIGMA = 7.0

private enum class EdgeType {
    START,
    END,
}

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

private fun correctSingleEdge(
    states: MutableList<SmoothedState>,
    anchors: List<CsfAnchor>,
    segFirstDay: Int,
    edge: EdgeType,
    edgeLocalDay: Int,
    totalDays: Int,
) {
    val fitRadius = 30
    val dayStart: Int
    val dayEnd: Int
    if (edge == EdgeType.END) {
        dayStart = segFirstDay + edgeLocalDay - fitRadius
        dayEnd = segFirstDay + edgeLocalDay
    } else {
        dayStart = segFirstDay
        dayEnd = segFirstDay + fitRadius
    }

    val regression = buildUnwrappedHours(anchors, dayStart, dayEnd) ?: return
    val edgeEnd = if (edge == EdgeType.END) min(edgeLocalDay, totalDays) else EDGE_WINDOW - 1
    val edgeStart = if (edge == EdgeType.END) max(0, edgeLocalDay - EDGE_WINDOW) else 0

    for (localD in edgeStart..edgeEnd) {
        val state = states.getOrNull(localD) ?: continue
        val globalD = segFirstDay + localD

        var wResSum = 0.0
        var wSum = 0.0
        for (anchor in regression.unwrappedHours) {
            val dist = abs(anchor.dayNumber - globalD)
            if (dist > ANCHOR_HALF_WINDOW) continue
            val gw = exp(-0.5 * (dist / ANCHOR_SIGMA) * (dist / ANCHOR_SIGMA))
            val w = gw * anchor.weight
            val trendAtAnchor = regression.slope * anchor.dayNumber + regression.intercept
            wResSum += w * (anchor.clockHour - trendAtAnchor)
            wSum += w
        }

        if (wSum < 0.5) continue

        val targetClockHour = regression.slope * globalD + regression.intercept + wResSum / wSum
        val currentClockHour = normalizeAngle(state.smoothedPhase)
        var correction = targetClockHour - currentClockHour
        while (correction > 12.0) correction -= 24.0
        while (correction < -12.0) correction += 24.0

        val t = if (edge == EdgeType.END) {
            if (EDGE_WINDOW > 0) (localD - edgeStart).toDouble() / EDGE_WINDOW else 1.0
        } else {
            if (EDGE_WINDOW > 0) 1.0 - localD.toDouble() / EDGE_WINDOW else 1.0
        }
        val blendWeight = t * t
        states[localD] = state.withSmoothed(smoothedPhase = state.smoothedPhase + blendWeight * correction)
    }

    if (edge == EdgeType.END && edgeLocalDay < totalDays) {
        val lastDataState = states.getOrNull(edgeLocalDay) ?: return
        val lastDataClockHour = normalizeAngle(lastDataState.smoothedPhase)

        for (localD in edgeLocalDay + 1..totalDays) {
            val state = states.getOrNull(localD) ?: continue
            val dist = localD - edgeLocalDay
            val forecastClockHour = lastDataClockHour + regression.slope * dist
            val currentClockHour = normalizeAngle(state.smoothedPhase)
            var correction = forecastClockHour - currentClockHour
            while (correction > 12.0) correction -= 24.0
            while (correction < -12.0) correction += 24.0

            states[localD] = state.withSmoothed(
                smoothedPhase = state.smoothedPhase + correction,
                smoothedTau = 24.0 + regression.slope,
            )
        }
    }
}

private fun correctStartEdgeFromInterior(states: MutableList<SmoothedState>) {
    val refStart = 15
    val refEnd = 25
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

    for (localD in 0 until min(EDGE_WINDOW, states.size)) {
        val state = states[localD]
        val targetPhase = slope * localD + intercept
        val correction = (targetPhase - state.smoothedPhase).coerceIn(-6.0, 6.0)
        val t = if (EDGE_WINDOW > 0) 1.0 - localD.toDouble() / EDGE_WINDOW else 1.0
        states[localD] = state.withSmoothed(smoothedPhase = state.smoothedPhase + t * t * correction)
    }
}

fun correctEdges(
    states: MutableList<SmoothedState>,
    anchors: List<CsfAnchor>,
    segFirstDay: Int,
    lastDataLocalDay: Int,
    totalDays: Int,
) {
    if (anchors.size < 3) return
    if (lastDataLocalDay < EDGE_WINDOW) return

    correctStartEdgeFromInterior(states)
    correctSingleEdge(states, anchors, segFirstDay, EdgeType.END, lastDataLocalDay, totalDays)
}

fun smoothOutputPhase(
    states: List<SmoothedState>,
    sigmaDays: Double = 2.0,
    halfWindow: Int = 3,
): List<SmoothedState> {
    if (states.size < 3) return states

    val smoothed = states.toMutableList()
    for (i in states.indices) {
        var phaseSum = 0.0
        var tauSum = 0.0
        var weightSum = 0.0

        for (j in max(0, i - halfWindow)..min(states.lastIndex, i + halfWindow)) {
            val dist = abs(j - i)
            val weight = exp(-0.5 * (dist / sigmaDays) * (dist / sigmaDays))
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
