package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import java.time.LocalDate
import kotlin.math.min

fun mergeSegmentResults(segments: List<SegmentResult>, globalFirstDate: LocalDate): CsfAnalysis {
    val empty = CsfAnalysis(
        globalTau = 24.0,
        globalDailyDrift = 0.0,
        days = emptyList(),
        algorithmId = ALGORITHM_ID,
        tau = 24.0,
        dailyDrift = 0.0,
        rSquared = 0.0,
        states = emptyList(),
        anchorCount = 0,
    )

    if (segments.isEmpty()) return empty

    val sortedSegments = segments.sortedBy { it.segFirstDay }
    val allDays = mutableListOf<CircadianDay>()
    val allStates = mutableListOf<SmoothedState>()
    val allResiduals = mutableListOf<Double>()
    var anchorCount = 0

    for (si in sortedSegments.indices) {
        val segment = sortedSegments[si]
        if (si > 0) {
            val prevEnd = sortedSegments[si - 1].segLastDay
            for (d in prevEnd + 1 until segment.segFirstDay) {
                allDays += CircadianDay(
                    date = globalFirstDate.plusDays(d.toLong()),
                    nightStartHour = 0.0,
                    nightEndHour = 0.0,
                    confidenceScore = 0.0,
                    confidence = CircadianConfidence.LOW,
                    localTau = 24.0,
                    localDrift = 0.0,
                    isForecast = false,
                    isGap = true,
                )
            }
        }

        allDays += segment.days
        allStates += segment.states
        allResiduals += segment.residuals
        anchorCount += segment.anchorCount
    }

    val overlayMids = mutableListOf<Triple<Int, Double, Double>>()
    var prevMid = Double.NEGATIVE_INFINITY
    for (day in allDays) {
        if (day.isForecast || day.isGap) continue
        var mid = (day.nightStartHour + day.nightEndHour) / 2.0
        if (prevMid > Double.NEGATIVE_INFINITY) {
            while (mid - prevMid > 12.0) mid -= 24.0
            while (prevMid - mid > 12.0) mid += 24.0
        }

        val globalD = daysBetween(globalFirstDate, day.date)
        overlayMids += Triple(globalD, mid, day.confidenceScore)
        prevMid = mid
    }

    val globalTau = if (overlayMids.size >= 2) {
        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        var sumWXX = 0.0
        var sumWXY = 0.0
        for ((xInt, y, w) in overlayMids) {
            val x = xInt.toDouble()
            sumW += w
            sumWX += w * x
            sumWY += w * y
            sumWXX += w * x * x
            sumWXY += w * x * y
        }
        val denom = sumW * sumWXX - sumWX * sumWX
        if (denom != 0.0 && sumW != 0.0) {
            24.0 + (sumW * sumWXY - sumWX * sumWY) / denom
        } else {
            24.0
        }
    } else {
        24.0
    }
    val globalDrift = globalTau - 24.0

    val sortedResiduals = allResiduals.sorted()
    val medResidual = if (sortedResiduals.isNotEmpty()) sortedResiduals[sortedResiduals.size / 2] else 0.0

    return CsfAnalysis(
        globalTau = globalTau,
        globalDailyDrift = globalDrift,
        days = allDays,
        algorithmId = ALGORITHM_ID,
        tau = globalTau,
        dailyDrift = globalDrift,
        rSquared = 1.0 - min(1.0, medResidual / 3.0),
        states = allStates,
        anchorCount = anchorCount,
    )
}
