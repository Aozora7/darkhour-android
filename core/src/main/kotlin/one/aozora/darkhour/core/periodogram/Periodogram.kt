package one.aozora.darkhour.core.periodogram

import one.aozora.darkhour.core.model.SleepRecord
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class PeriodogramAnchor(
    val dayNumber: Int,
    val midpointHour: Double,
    val weight: Double,
)

data class PeriodogramPoint(
    val period: Double,
    val power: Double,
)

data class PeriodogramOptions(
    val minPeriod: Double = 23.0,
    val maxPeriod: Double = 26.0,
    val step: Double = 0.01,
)

data class PeriodogramResult(
    val points: List<PeriodogramPoint>,
    val trimmedPoints: List<PeriodogramPoint>,
    val peakPeriod: Double,
    val peakPower: Double,
    val significanceThreshold: Double,
    val power24h: Double,
) {
    companion object {
        val Empty = PeriodogramResult(
            points = emptyList(),
            trimmedPoints = emptyList(),
            peakPeriod = 24.0,
            peakPower = 0.0,
            significanceThreshold = 0.0,
            power24h = 0.0,
        )
    }
}

fun buildPeriodogramAnchors(records: List<SleepRecord>): List<PeriodogramAnchor> {
    if (records.isEmpty()) return emptyList()

    val sorted = records.sortedBy { it.startTime }
    val firstDate = sorted.first().dateOfSleep

    return sorted
        .filter { it.isMainSleep && it.durationHours >= 4.0 }
        .map { record ->
            val offset = record.startZoneOffset ?: ZoneOffset.UTC
            val midnight = record.dateOfSleep.atStartOfDay().toInstant(offset)
            val midpointMs = (record.startTime.toEpochMilli() + record.endTime.toEpochMilli()) / 2.0
            PeriodogramAnchor(
                dayNumber = ChronoUnit.DAYS.between(firstDate, record.dateOfSleep).toInt(),
                midpointHour = (midpointMs - midnight.toEpochMilli()) / 3_600_000.0,
                weight = (record.sleepScore ?: 0.0) * min(1.0, record.durationHours / 7.0),
            )
        }
}

private fun gaussianSmooth(values: List<Double>, sigma: Double): List<Double> {
    val radius = ceil(sigma * 3.0).toInt()
    return values.indices.map { i ->
        var sum = 0.0
        var weightSum = 0.0
        for (j in -radius..radius) {
            val index = i + j
            if (index !in values.indices) continue
            val weight = exp(-0.5 * (j / sigma) * (j / sigma))
            sum += weight * values[index]
            weightSum += weight
        }
        sum / weightSum
    }
}

fun computePeriodogram(
    anchors: List<PeriodogramAnchor>,
    options: PeriodogramOptions = PeriodogramOptions(),
): PeriodogramResult {
    if (anchors.size < 3) return PeriodogramResult.Empty
    require(options.step > 0.0)
    require(options.maxPeriod >= options.minPeriod)

    val times = anchors.map { it.dayNumber * 24.0 + it.midpointHour }
    val weights = anchors.map { it.weight }
    val dayNumbers = anchors.map { it.dayNumber }
    val firstDay = dayNumbers.first()
    val lastDay = dayNumbers.last()
    val spanDays = lastDay - firstDay

    data class Window(val indices: List<Int>, val totalWeight: Double)

    val windowDays = 120
    val windowStep = 30
    val minAnchors = 8
    val windows = mutableListOf<Window>()

    if (spanDays <= windowDays * 1.5) {
        windows += Window(anchors.indices.toList(), weights.sum())
    } else {
        var center = firstDay + windowDays / 2
        while (center <= lastDay - windowDays / 2 + windowStep) {
            val indices = anchors.indices.filter { abs(dayNumbers[it] - center) <= windowDays / 2 }
            if (indices.size >= minAnchors) {
                windows += Window(indices, indices.sumOf { weights[it] })
            }
            center += windowStep
        }
    }

    if (windows.isEmpty() || windows.any { it.totalWeight <= 0.0 }) {
        return PeriodogramResult.Empty
    }

    val effectiveSizes = windows.map { window ->
        val totalWeight = window.indices.sumOf { weights[it] }
        val squaredWeight = window.indices.sumOf { weights[it] * weights[it] }
        if (squaredWeight > 0.0) totalWeight * totalWeight / squaredWeight else 0.0
    }.sorted()
    val medianEffectiveSize = effectiveSizes[effectiveSizes.size / 2]
    if (medianEffectiveSize <= 0.0) return PeriodogramResult.Empty

    val trialCount = ((options.maxPeriod - options.minPeriod) / options.step).roundToInt() + 1
    val periods = List(trialCount) { options.minPeriod + it * options.step }
    val power = periods.map { period ->
        windows.sumOf { window ->
            var sumCos = 0.0
            var sumSin = 0.0
            for (index in window.indices) {
                val phase = ((times[index] % period) + period) % period
                val theta = 2.0 * PI * phase / period
                val weight = weights[index]
                sumCos += weight * cos(theta)
                sumSin += weight * sin(theta)
            }
            val c = sumCos / window.totalWeight
            val s = sumSin / window.totalWeight
            c * c + s * s
        } / windows.size
    }
    val smoothedPower = gaussianSmooth(power, sigma = 3.0)

    val points = periods.indices.map { PeriodogramPoint(periods[it], smoothedPower[it]) }
    val peak = points.maxBy { it.power }
    val power24h = points.minBy { abs(it.period - 24.0) }.power
    val significanceThreshold = -ln(0.01) / medianEffectiveSize

    val significant = points.filter { it.power > significanceThreshold }
    var displayMin: Double
    var displayMax: Double
    if (significant.isNotEmpty()) {
        displayMin = significant.minOf { it.period } - 0.25
        displayMax = significant.maxOf { it.period } + 0.25
    } else {
        displayMin = peak.period - 1.0
        displayMax = peak.period + 1.0
    }

    displayMin = min(displayMin, 23.75)
    displayMax = max(displayMax, 24.25)
    if (displayMax - displayMin < 2.0) {
        val center = (displayMin + displayMax) / 2.0
        displayMin = center - 1.0
        displayMax = center + 1.0
    }
    displayMin = max(options.minPeriod, displayMin)
    displayMax = min(options.maxPeriod, displayMax)

    return PeriodogramResult(
        points = points,
        trimmedPoints = points.filter { it.period in displayMin..displayMax },
        peakPeriod = peak.period,
        peakPower = peak.power,
        significanceThreshold = significanceThreshold,
        power24h = power24h,
    )
}
