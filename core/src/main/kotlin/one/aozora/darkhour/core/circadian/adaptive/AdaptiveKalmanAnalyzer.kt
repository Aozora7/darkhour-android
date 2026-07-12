package one.aozora.darkhour.core.circadian.adaptive

import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.circadian.DurationObservation
import one.aozora.darkhour.core.circadian.DurationSmoothingConfig
import one.aozora.darkhour.core.circadian.prepareWeightedMidpointAnchors
import one.aozora.darkhour.core.circadian.smoothDurations
import one.aozora.darkhour.core.circadian.splitIntoSegments
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.max

const val ADAPTIVE_KALMAN_ALGORITHM_ID = "adaptive-kalman-v1"

data class AdaptiveKalmanAnalysis(
    override val globalTau: Double,
    override val globalDailyDrift: Double,
    override val days: List<CircadianDay>,
    override val algorithmId: String = ADAPTIVE_KALMAN_ALGORITHM_ID,
    override val tau: Double = globalTau,
    override val dailyDrift: Double = globalDailyDrift,
    override val rSquared: Double = 0.0,
    val anchorCount: Int,
    val transitionEvidenceDays: List<LocalDate>,
) : CircadianAnalysis

fun analyzeCircadianAdaptiveKalman(
    records: List<SleepRecord>,
    extraDays: Int = 0,
    config: AdaptiveKalmanConfig = AdaptiveKalmanConfig(),
    durationSmoothing: DurationSmoothingConfig = DurationSmoothingConfig(),
): AdaptiveKalmanAnalysis {
    if (records.isEmpty()) return emptyAnalysis()
    val sorted = records.sortedBy(SleepRecord::startTime)
    val firstDate = sorted.first().dateOfSleep
    val offset = sorted.first().startZoneOffset ?: sorted.first().endZoneOffset ?: ZoneOffset.UTC
    val firstDateMs = firstDate.atStartOfDay().toInstant(offset).toEpochMilli()
    val recordSegments = splitIntoSegments(sorted)
    val segments = recordSegments.mapIndexedNotNull { index, segment ->
        analyzeSegment(
            records = segment,
            globalFirstDate = firstDate,
            globalFirstDateMs = firstDateMs,
            extraDays = if (index == recordSegments.lastIndex) extraDays else 0,
            config = config,
            durationSmoothing = durationSmoothing,
        )
    }
    if (segments.isEmpty()) return emptyAnalysis()
    val days = buildList {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                for (day in segments[index - 1].dataEndDay + 1 until segment.firstDay) {
                    add(gapDay(firstDate.plusDays(day.toLong())))
                }
            }
            addAll(segment.days)
        }
    }
    val observed = days.filterNot { it.isGap || it.isForecast }
    val globalTau = observed.map(CircadianDay::localTau).average().takeIf(Double::isFinite) ?: 24.0
    return AdaptiveKalmanAnalysis(
        globalTau = globalTau,
        globalDailyDrift = globalTau - 24.0,
        days = days,
        anchorCount = segments.sumOf(SegmentResult::anchorCount),
        transitionEvidenceDays = segments.flatMap(SegmentResult::transitionEvidenceDays),
    )
}

private fun analyzeSegment(
    records: List<SleepRecord>,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
    extraDays: Int,
    config: AdaptiveKalmanConfig,
    durationSmoothing: DurationSmoothingConfig,
): SegmentResult? {
    val anchors = prepareWeightedMidpointAnchors(records, globalFirstDate, globalFirstDateMs)
    if (anchors.size < 2) return null
    val firstDay = anchors.first().dayNumber
    val dataEndDay = anchors.last().dayNumber
    val states = fitAdaptiveKalmanTrend(
        observations = anchors.map { AdaptiveKalmanObservation(it.dayNumber, it.midpointHour, it.weight) },
        firstDay = firstDay,
        lastDay = dataEndDay + extraDays,
        config = config,
    )
    val durations = smoothDurations(
        observations = anchors.map { DurationObservation(it.dayNumber, it.record.durationHours) },
        targetDays = firstDay..states.last().dayNumber,
        config = durationSmoothing,
    )
    val anchorByDay = anchors.associateBy { it.dayNumber }
    return SegmentResult(
        firstDay = firstDay,
        dataEndDay = dataEndDay,
        anchorCount = anchors.size,
        transitionEvidenceDays = states.filter { it.transitionEvidence > 0.0 }
            .map { globalFirstDate.plusDays(it.dayNumber.toLong()) },
        days = states.mapIndexed { index, state ->
            val forecastDistance = (state.dayNumber - dataEndDay).coerceAtLeast(0)
            val confidenceScore = if (forecastDistance > 0) {
                max(0.1, 0.5 * exp(-0.1 * forecastDistance))
            } else {
                1.0 / (1.0 + state.phaseVariance)
            }
            val midpoint = normalizeClockHour(state.phase)
            val duration = durations[index]
            CircadianDay(
                date = globalFirstDate.plusDays(state.dayNumber.toLong()),
                nightStartHour = midpoint - duration / 2.0,
                nightEndHour = midpoint + duration / 2.0,
                confidenceScore = confidenceScore,
                confidence = when {
                    confidenceScore >= 0.6 -> CircadianConfidence.HIGH
                    confidenceScore >= 0.3 -> CircadianConfidence.MEDIUM
                    else -> CircadianConfidence.LOW
                },
                localTau = 24.0 + state.drift,
                localDrift = state.drift,
                anchorSleep = anchorByDay[state.dayNumber]?.record,
                isForecast = forecastDistance > 0,
                isGap = false,
            )
        },
    )
}

private data class SegmentResult(
    val firstDay: Int,
    val dataEndDay: Int,
    val anchorCount: Int,
    val transitionEvidenceDays: List<LocalDate>,
    val days: List<CircadianDay>,
)

private fun gapDay(date: LocalDate) = CircadianDay(
    date = date,
    nightStartHour = 0.0,
    nightEndHour = 0.0,
    confidenceScore = 0.0,
    confidence = CircadianConfidence.LOW,
    localTau = 24.0,
    localDrift = 0.0,
    isForecast = false,
    isGap = true,
)

private fun normalizeClockHour(hour: Double): Double = ((hour % 24.0) + 24.0) % 24.0

private fun emptyAnalysis() = AdaptiveKalmanAnalysis(
    globalTau = 24.0,
    globalDailyDrift = 0.0,
    days = emptyList(),
    anchorCount = 0,
    transitionEvidenceDays = emptyList(),
)
