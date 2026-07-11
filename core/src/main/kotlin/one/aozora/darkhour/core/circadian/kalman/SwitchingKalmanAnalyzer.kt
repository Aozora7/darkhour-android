package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.circadian.DurationObservation
import one.aozora.darkhour.core.circadian.smoothDurations
import one.aozora.darkhour.core.circadian.splitIntoSegments
import one.aozora.darkhour.core.circadian.prepareWeightedMidpointAnchors
import one.aozora.darkhour.core.circadian.WeightedMidpointAnchor
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.max

const val SWITCHING_KALMAN_ALGORITHM_ID = "switching-kalman-v1"
private const val TAU_MIN = 22.0
private const val TAU_MAX = 27.0

data class SwitchingKalmanAnalysis(
    override val globalTau: Double,
    override val globalDailyDrift: Double,
    override val days: List<CircadianDay>,
    override val algorithmId: String,
    override val tau: Double,
    override val dailyDrift: Double,
    override val rSquared: Double,
    val anchorCount: Int,
    val changePoints: List<SwitchingKalmanChangePoint>,
) : CircadianAnalysis

data class SwitchingKalmanChangePoint(
    val date: LocalDate,
    val confirmationDate: LocalDate,
    val posteriorProbability: Double,
    val previousDrift: Double,
    val newDrift: Double,
    val observationOffset: Double,
)

fun analyzeCircadianSwitchingKalman(
    records: List<SleepRecord>,
    extraDays: Int = 0,
    config: SwitchingKalmanConfig = SwitchingKalmanConfig(),
): SwitchingKalmanAnalysis {
    if (records.isEmpty()) return emptySwitchingAnalysis()
    val sorted = records.sortedBy(SleepRecord::startTime)
    val firstDate = sorted.first().dateOfSleep
    val zoneOffset = sorted.first().startZoneOffset ?: sorted.first().endZoneOffset ?: ZoneOffset.UTC
    val firstDateMs = firstDate.atStartOfDay().toInstant(zoneOffset).toEpochMilli()
    val segments = splitIntoSegments(sorted)
    val fitted = segments.mapIndexedNotNull { index, segment ->
        analyzeSwitchingSegment(
            records = segment,
            firstDate = firstDate,
            firstDateMs = firstDateMs,
            extraDays = if (index == segments.lastIndex) extraDays else 0,
            config = config,
        )
    }
    if (fitted.isEmpty()) return emptySwitchingAnalysis()
    val days = mutableListOf<CircadianDay>()
    fitted.forEachIndexed { index, segment ->
        if (index > 0) {
            for (day in fitted[index - 1].dataEndDay + 1 until segment.firstDay) {
                days += switchingGapDay(firstDate.plusDays(day.toLong()))
            }
        }
        days += segment.days
    }
    val observed = days.filterNot { it.isGap || it.isForecast }
    val globalTau = observed.map(CircadianDay::localTau).average().takeIf(Double::isFinite) ?: 24.0
    return SwitchingKalmanAnalysis(
        globalTau = globalTau,
        globalDailyDrift = globalTau - 24.0,
        days = days,
        algorithmId = SWITCHING_KALMAN_ALGORITHM_ID,
        tau = globalTau,
        dailyDrift = globalTau - 24.0,
        rSquared = 0.0,
        anchorCount = fitted.sumOf(SwitchingSegment::anchorCount),
        changePoints = fitted.flatMap(SwitchingSegment::changePoints),
    )
}

private fun analyzeSwitchingSegment(
    records: List<SleepRecord>,
    firstDate: LocalDate,
    firstDateMs: Long,
    extraDays: Int,
    config: SwitchingKalmanConfig,
): SwitchingSegment? {
    val anchors = prepareWeightedMidpointAnchors(records, firstDate, firstDateMs)
    if (anchors.size < 2) return null
    val observations = anchors.map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) }
    val dataEndDay = anchors.last().dayNumber
    val detected = detectSwitchingKalmanChangePoints(observations, config)
    val starts = listOf(anchors.first().dayNumber) + detected.map(DetectedSwitchingChangePoint::dayNumber)
    val states = mutableListOf<SwitchingTrendState>()
    starts.forEachIndexed { index, regimeStart ->
        val observedEnd = detected.getOrNull(index)?.dayNumber?.minus(1) ?: dataEndDay
        val stateEnd = if (index == starts.lastIndex) dataEndDay + extraDays else observedEnd
        val regimeObservations = observations.filter { it.dayNumber in regimeStart..observedEnd }
        val change = detected.getOrNull(index - 1)
        val initial = change?.let {
            val previous = states.last()
            initialSwitchingState(
                phase = previous.phase + previous.drift,
                drift = it.newDrift,
                offset = it.observationOffset,
                config = config,
            )
        }
        states += fitSwitchingKalmanTrend(
            observations = regimeObservations,
            firstDay = regimeStart,
            lastDay = stateEnd,
            config = config,
            initialState = initial,
        )
    }
    val firstStateDay = states.first().dayNumber
    val durations = smoothDurations(
        observations = anchors.map { DurationObservation(it.dayNumber, it.record.durationHours) },
        targetDays = firstStateDay..states.last().dayNumber,
        config = config.durationSmoothing,
    ).withIndex().associate { (index, duration) -> firstStateDay + index to duration }
    val anchorByDay = anchors.associateBy(WeightedMidpointAnchor::dayNumber)
    return SwitchingSegment(
        firstDay = anchors.first().dayNumber,
        dataEndDay = dataEndDay,
        anchorCount = anchors.size,
        changePoints = detected.map {
            SwitchingKalmanChangePoint(
                date = firstDate.plusDays(it.dayNumber.toLong()),
                confirmationDate = firstDate.plusDays(it.confirmationDayNumber.toLong()),
                posteriorProbability = it.posteriorProbability,
                previousDrift = it.previousDrift,
                newDrift = it.newDrift,
                observationOffset = it.observationOffset,
            )
        },
        days = states.map { state ->
            val forecast = state.dayNumber > dataEndDay
            val confidenceScore = if (forecast) {
                max(0.1, 0.5 * exp(-0.1 * (state.dayNumber - dataEndDay)))
            } else {
                1.0 / (1.0 + state.phaseVariance)
            }
            val midpoint = normalizeSwitchingHour(state.phase + state.offset)
            val duration = durations.getValue(state.dayNumber)
            val tau = (24.0 + state.drift).coerceIn(TAU_MIN, TAU_MAX)
            CircadianDay(
                date = firstDate.plusDays(state.dayNumber.toLong()),
                nightStartHour = midpoint - duration / 2.0,
                nightEndHour = midpoint + duration / 2.0,
                confidenceScore = confidenceScore,
                confidence = when {
                    confidenceScore >= 0.6 -> CircadianConfidence.HIGH
                    confidenceScore >= 0.3 -> CircadianConfidence.MEDIUM
                    else -> CircadianConfidence.LOW
                },
                localTau = tau,
                localDrift = tau - 24.0,
                anchorSleep = anchorByDay[state.dayNumber]?.record,
                isForecast = forecast,
                isGap = false,
            )
        },
    )
}

private data class SwitchingSegment(
    val firstDay: Int,
    val dataEndDay: Int,
    val anchorCount: Int,
    val changePoints: List<SwitchingKalmanChangePoint>,
    val days: List<CircadianDay>,
)

private fun switchingGapDay(date: LocalDate) = CircadianDay(
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

private fun normalizeSwitchingHour(hour: Double): Double = ((hour % 24.0) + 24.0) % 24.0

private fun emptySwitchingAnalysis() = SwitchingKalmanAnalysis(
    globalTau = 24.0,
    globalDailyDrift = 0.0,
    days = emptyList(),
    algorithmId = SWITCHING_KALMAN_ALGORITHM_ID,
    tau = 24.0,
    dailyDrift = 0.0,
    rSquared = 0.0,
    anchorCount = 0,
    changePoints = emptyList(),
)
