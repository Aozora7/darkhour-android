package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.circadian.DurationObservation
import one.aozora.darkhour.core.circadian.smoothDurations
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

fun analyzeSegment(
    records: List<SleepRecord>,
    extraDays: Int,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
    smoothing: CsfSmoothingConfig,
    config: CsfConfig = CsfConfig.Default,
): SegmentResult? {
    if (records.isEmpty()) return null

    val anchors = prepareAnchors(records, globalFirstDate, globalFirstDateMs)
    if (anchors.size < MIN_ANCHORS) return null

    val firstAnchor = anchors.first()
    val lastAnchor = anchors.last()
    val segFirstDay = firstAnchor.dayNumber
    val lastRecordDay = daysBetween(globalFirstDate, records.last().dateOfSleep)
    val segLastDayWithForecast = max(lastAnchor.dayNumber, lastRecordDay) + extraDays
    val totalDays = segLastDayWithForecast - segFirstDay

    val forwardStates = forwardPass(anchors, segFirstDay, segLastDayWithForecast, config)
    val smoothedStates = rtsSmoother(forwardStates, config)
    val outputStates = smoothOutputStates(smoothedStates, smoothing).toMutableList()
    val lastDataLocalDay = max(lastAnchor.dayNumber, lastRecordDay) - segFirstDay
    correctEdges(outputStates, anchors, segFirstDay, lastDataLocalDay, totalDays, smoothing)

    val anchorByDay = anchors.associateBy { it.dayNumber }
    val smoothedDurations = smoothDurations(
        observations = anchors.map { DurationObservation(it.dayNumber, it.record.durationHours) },
        targetDays = segFirstDay..segLastDayWithForecast,
        config = config.durationSmoothing,
    )

    val days = mutableListOf<CircadianDay>()
    val residuals = mutableListOf<Double>()

    for (localD in 0..totalDays) {
        val globalD = segFirstDay + localD
        val state = outputStates.getOrNull(localD) ?: continue
        val date = globalFirstDate.plusDays(globalD.toLong())

        val predictedMid = state.smoothedPhase
        val localTau = state.smoothedTau
        val localDrift = localTau - 24.0
        val anchor = anchorByDay[globalD]
        val isForecast = globalD > segLastDayWithForecast - extraDays
        val halfDur = (smoothedDurations.getOrNull(localD) ?: 8.0) / 2.0

        val confScore = if (isForecast) {
            val distFromEdge = globalD - (segLastDayWithForecast - extraDays)
            max(0.1, 0.5 * exp(-0.1 * distFromEdge))
        } else {
            val density = min(1.0, 1.0 / max(state.smoothedPhaseVar, 0.1))
            min(1.0, density * (1.0 - min(1.0, state.smoothedPhaseVar / 2.0)))
        }

        val normalizedMid = normalizeAngle(predictedMid)
        days += CircadianDay(
            date = date,
            nightStartHour = normalizedMid - halfDur,
            nightEndHour = normalizedMid + halfDur,
            confidenceScore = confScore,
            confidence = when {
                confScore >= 0.6 -> CircadianConfidence.HIGH
                confScore >= 0.3 -> CircadianConfidence.MEDIUM
                else -> CircadianConfidence.LOW
            },
            localTau = localTau.coerceIn(TAU_MIN, TAU_MAX),
            localDrift = localDrift.coerceIn(-1.5, 3.0),
            anchorSleep = anchor?.record,
            isForecast = isForecast,
            isGap = false,
        )

        if (!isForecast && anchor != null) {
            residuals += abs(circularDiff(anchor.midpointHour, predictedMid))
        }
    }

    return SegmentResult(
        days = days,
        states = smoothedStates,
        anchorCount = anchors.size,
        residuals = residuals,
        segFirstDay = segFirstDay,
        segLastDay = segLastDayWithForecast - extraDays,
    )
}
