package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStages
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun assertClose(actual: Double, expected: Double, tolerance: Double = 1e-6) {
    assertTrue("Expected $actual to be within $tolerance of $expected", abs(actual - expected) <= tolerance)
}

internal fun makeSleepRecord(
    logId: Long = 1,
    dateOfSleep: LocalDate = LocalDate.parse("2024-03-15"),
    startTime: Instant = Instant.parse("2024-03-15T23:00:00Z"),
    endTime: Instant = startTime.plusMillis(8L * 3_600_000L),
    durationMs: Long = 8L * 3_600_000L,
    durationHours: Double = durationMs / 3_600_000.0,
    efficiency: Int = 90,
    minutesAsleep: Int = 420,
    minutesAwake: Int = 30,
    isMainSleep: Boolean = true,
    sleepScore: Double? = 0.85,
    stages: SleepStages? = null,
    stageData: List<SleepStageInterval> = emptyList(),
    startZoneOffset: ZoneOffset? = null,
    endZoneOffset: ZoneOffset? = null,
): SleepRecord = SleepRecord(
    logId = logId,
    dateOfSleep = dateOfSleep,
    startTime = startTime,
    endTime = endTime,
    durationMs = durationMs,
    durationHours = durationHours,
    efficiency = efficiency,
    minutesAsleep = minutesAsleep,
    minutesAwake = minutesAwake,
    isMainSleep = isMainSleep,
    sleepScore = sleepScore,
    stages = stages,
    stageData = stageData,
    startZoneOffset = startZoneOffset,
    endZoneOffset = endZoneOffset,
)

private class Mulberry32(seed: Int) {
    private var state = seed

    fun next(): Double {
        state = state or 0
        state += 0x6d2b79f5
        var t = state
        t = (t xor (t ushr 15)) * (1 or t)
        t = t xor ((t xor (t ushr 7)) * (61 or t))
        return ((t xor (t ushr 14)).toUInt().toDouble()) / 4294967296.0
    }
}

private fun gaussianSample(rng: Mulberry32): Double {
    val u1 = rng.next()
    val u2 = rng.next()
    return sqrt(-2.0 * ln(u1 + 1e-10)) * cos(2.0 * Math.PI * u2)
}

internal data class TauSegment(val untilDay: Int, val tau: Double)

internal data class FragmentedPeriod(
    val startDay: Int,
    val endDay: Int,
    val boutsPerDay: Int,
    val boutDuration: Double,
)

internal data class SyntheticOptions(
    val tau: Double = 24.5,
    val days: Int = 90,
    val baseDuration: Double = 8.0,
    val noise: Double = 0.5,
    val gapFraction: Double = 0.0,
    val startMidpoint: Double = 3.0,
    val seed: Int = 42,
    val quality: Double = 0.8,
    val tauSegments: List<TauSegment> = emptyList(),
    val napFraction: Double = 0.0,
    val outlierFraction: Double = 0.0,
    val outlierOffset: Double = 6.0,
    val fragmentedPeriods: List<FragmentedPeriod> = emptyList(),
    val startDate: LocalDate = LocalDate.parse("2024-01-01"),
)

internal fun computeTrueMidpoint(day: Int, opts: SyntheticOptions = SyntheticOptions()): Double {
    if (opts.tauSegments.isEmpty()) {
        return opts.startMidpoint + day * (opts.tau - 24.0)
    }

    var midpoint = opts.startMidpoint
    var prevDay = 0
    for (segment in opts.tauSegments) {
        val segEnd = minOf(day, segment.untilDay)
        if (prevDay >= segEnd) {
            prevDay = segment.untilDay
            continue
        }
        midpoint += (segEnd - prevDay) * (segment.tau - 24.0)
        prevDay = segEnd
        if (prevDay >= day) break
    }
    return midpoint
}

internal fun generateSyntheticRecords(opts: SyntheticOptions = SyntheticOptions()): List<SleepRecord> {
    val rng = Mulberry32(opts.seed)
    val records = mutableListOf<SleepRecord>()
    val baseDate = opts.startDate
    var nextLogId = 1000L

    for (d in 0 until opts.days) {
        if (opts.gapFraction > 0.0 && rng.next() < opts.gapFraction) {
            rng.next()
            rng.next()
            rng.next()
            continue
        }

        val midpointTrue = computeTrueMidpoint(d, opts)
        val dayDate = baseDate.plusDays(d.toLong())
        val frag = opts.fragmentedPeriods.find { d >= it.startDay && d < it.endDay }

        if (frag != null) {
            val numBouts = max(1, frag.boutsPerDay + ((rng.next() - 0.5) * 3.0).toInt())
            val boutMidpoints = MutableList(numBouts) {
                midpointTrue + (rng.next() - 0.5) * 20.0 + gaussianSample(rng) * 2.0
            }
            val closestIdx = boutMidpoints.indices.minBy { abs(boutMidpoints[it] - midpointTrue) }

            for (b in 0 until numBouts) {
                val boutMid = boutMidpoints[b]
                val durVariation = (rng.next() - 0.5) * frag.boutDuration * 0.6
                val thisDuration = max(0.5, frag.boutDuration + durVariation)
                val halfDur = thisDuration / 2.0
                val startMs = dayDate.utcStartInstant().toEpochMilli() + ((boutMid - halfDur) * 3_600_000.0).toLong()
                val endMs = dayDate.utcStartInstant().toEpochMilli() + ((boutMid + halfDur) * 3_600_000.0).toLong()
                val durationMs = endMs - startMs
                val isMain = b == closestIdx
                val eff = if (isMain) 80.0 + rng.next() * 15.0 else 60.0 + rng.next() * 20.0
                records += makeSleepRecord(
                    logId = nextLogId++,
                    dateOfSleep = dayDate,
                    startTime = Instant.ofEpochMilli(startMs),
                    endTime = Instant.ofEpochMilli(endMs),
                    durationMs = durationMs,
                    durationHours = durationMs / 3_600_000.0,
                    efficiency = eff.roundToInt(),
                    minutesAsleep = ((durationMs / 60_000.0) * (eff / 100.0)).roundToInt(),
                    minutesAwake = ((durationMs / 60_000.0) * (1.0 - eff / 100.0)).roundToInt(),
                    isMainSleep = isMain,
                    sleepScore = if (isMain) opts.quality * (0.5 + rng.next() * 0.3) else opts.quality * (0.3 + rng.next() * 0.2),
                )
            }
            rng.next()
            rng.next()
            continue
        }

        val midpointHour = midpointTrue + gaussianSample(rng) * opts.noise
        val isOutlier = opts.outlierFraction > 0.0 && rng.next() < opts.outlierFraction
        val finalMidpoint = if (isOutlier) midpointHour + opts.outlierOffset else midpointHour
        val durationHours = opts.baseDuration + (rng.next() - 0.5)
        val halfDur = durationHours / 2.0
        val startMs = dayDate.utcStartInstant().toEpochMilli() + ((finalMidpoint - halfDur) * 3_600_000.0).toLong()
        val endMs = dayDate.utcStartInstant().toEpochMilli() + ((finalMidpoint + halfDur) * 3_600_000.0).toLong()
        val durationMs = endMs - startMs

        records += makeSleepRecord(
            logId = nextLogId++,
            dateOfSleep = dayDate,
            startTime = Instant.ofEpochMilli(startMs),
            endTime = Instant.ofEpochMilli(endMs),
            durationMs = durationMs,
            durationHours = durationMs / 3_600_000.0,
            efficiency = 90,
            minutesAsleep = ((durationMs / 60_000.0) * 0.9).roundToInt(),
            minutesAwake = ((durationMs / 60_000.0) * 0.1).roundToInt(),
            isMainSleep = true,
            sleepScore = opts.quality,
        )

        if (opts.napFraction > 0.0 && rng.next() < opts.napFraction) {
            val napDuration = 2.0 + rng.next()
            val napMidpoint = midpointTrue + 12.0 + gaussianSample(rng) * 2.0
            val napHalfDur = napDuration / 2.0
            val napStartMs = dayDate.utcStartInstant().toEpochMilli() + ((napMidpoint - napHalfDur) * 3_600_000.0).toLong()
            val napEndMs = dayDate.utcStartInstant().toEpochMilli() + ((napMidpoint + napHalfDur) * 3_600_000.0).toLong()
            val napDurMs = napEndMs - napStartMs
            records += makeSleepRecord(
                logId = nextLogId++,
                dateOfSleep = dayDate,
                startTime = Instant.ofEpochMilli(napStartMs),
                endTime = Instant.ofEpochMilli(napEndMs),
                durationMs = napDurMs,
                durationHours = napDurMs / 3_600_000.0,
                efficiency = 80,
                minutesAsleep = ((napDurMs / 60_000.0) * 0.85).roundToInt(),
                minutesAwake = ((napDurMs / 60_000.0) * 0.15).roundToInt(),
                isMainSleep = false,
                sleepScore = 0.5,
            )
        }
    }

    return records.sortedBy { it.startTime }
}

internal fun assertAnalysisTau(analysis: CsfAnalysis, expectedTau: Double, tolerance: Double) {
    assertEquals(ALGORITHM_ID, analysis.algorithmId)
    assertTrue("Expected anchor count > 0", analysis.anchorCount > 0)
    assertTrue("Expected tau ${analysis.globalTau} near $expectedTau", abs(analysis.globalTau - expectedTau) < tolerance)
}

internal fun analyzeCircadianCsf(
    records: List<SleepRecord>,
    extraDays: Int = 0,
): CsfAnalysis {
    val smoothingDays = CircadianAlgorithmRegistry
        .resolvedValues(CircadianAlgorithmRegistry.CSF_ID, emptyMap())
        .getValue("smoothing_days")
    return analyzeCircadianCsf(
        records = records,
        smoothing = CsfSmoothingConfig(smoothingDays),
        extraDays = extraDays,
    )
}
