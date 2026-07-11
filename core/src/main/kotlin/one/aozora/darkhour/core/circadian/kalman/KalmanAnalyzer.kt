package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.DurationObservation
import one.aozora.darkhour.core.circadian.splitIntoSegments
import one.aozora.darkhour.core.circadian.smoothDurations
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.max

const val KALMAN_ALGORITHM_ID = "unwrapped-kalman-v1"
private const val KALMAN_TAU_MIN = 22.0
private const val KALMAN_TAU_MAX = 27.0

data class KalmanAnalysis(
    override val globalTau: Double,
    override val globalDailyDrift: Double,
    override val days: List<CircadianDay>,
    override val algorithmId: String,
    override val tau: Double,
    override val dailyDrift: Double,
    override val rSquared: Double,
    val anchorCount: Int,
    val changePoints: List<KalmanChangePoint>,
) : CircadianAnalysis

data class KalmanChangePoint(
    val date: LocalDate,
    val previousDrift: Double,
    val newDrift: Double,
    val evidenceRatio: Double,
    val confirmationDate: LocalDate,
)

/** Production-compatible wrapper around the unwrapped Kalman trend model. */
fun analyzeCircadianKalman(
    records: List<SleepRecord>,
    extraDays: Int = 0,
    config: KalmanConfig = KalmanConfig(),
): KalmanAnalysis {
    if (records.isEmpty()) return emptyKalmanAnalysis()

    val sorted = records.sortedBy(SleepRecord::startTime)
    val firstDate = sorted.first().dateOfSleep
    val offset = sorted.first().startZoneOffset ?: sorted.first().endZoneOffset ?: ZoneOffset.UTC
    val firstDateMs = firstDate.atStartOfDay().toInstant(offset).toEpochMilli()
    val segments = splitIntoSegments(sorted)
    val results = segments.mapIndexedNotNull { index, segment ->
        analyzeKalmanSegment(segment, firstDate, firstDateMs, if (index == segments.lastIndex) extraDays else 0, config)
    }
    if (results.isEmpty()) return emptyKalmanAnalysis()

    val days = mutableListOf<CircadianDay>()
    for ((index, result) in results.withIndex()) {
        if (index > 0) {
            for (day in results[index - 1].dataEndDay + 1 until result.firstDay) {
                days += gapDay(firstDate.plusDays(day.toLong()))
            }
        }
        days += result.days
    }
    val observed = days.filter { !it.isForecast && !it.isGap }
    val globalTau = observed.map(CircadianDay::localTau).average().takeIf(Double::isFinite) ?: 24.0
    return KalmanAnalysis(
        globalTau = globalTau,
        globalDailyDrift = globalTau - 24.0,
        days = days,
        algorithmId = KALMAN_ALGORITHM_ID,
        tau = globalTau,
        dailyDrift = globalTau - 24.0,
        rSquared = 0.0,
        anchorCount = results.sumOf(KalmanSegment::anchorCount),
        changePoints = results.flatMap(KalmanSegment::changePoints),
    )
}

private fun analyzeKalmanSegment(
    records: List<SleepRecord>,
    firstDate: LocalDate,
    firstDateMs: Long,
    extraDays: Int,
    config: KalmanConfig,
): KalmanSegment? {
    val anchors = prepareKalmanAnchors(records, firstDate, firstDateMs)
    if (anchors.size < 2) return null
    // A session rejected as an anchor is not evidence that the fitted timing
    // trajectory continues.  In particular, trailing short/low-quality
    // sessions must not turn into months of apparent observed overlay.
    val dataEndDay = anchors.last().dayNumber
    val observations = anchors.map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) }
    val detected = detectKalmanChangePoints(
        observations = observations,
        firstDay = anchors.first().dayNumber,
        lastDay = dataEndDay,
        config = config,
    )
    val regimeStarts = listOf(anchors.first().dayNumber) + detected.map(DetectedKalmanChangePoint::dayNumber)
    val states = mutableListOf<KalmanTrendState>()
    regimeStarts.forEachIndexed { index, regimeStart ->
        val observedEnd = detected.getOrNull(index)?.dayNumber?.minus(1) ?: dataEndDay
        val stateEnd = if (index == regimeStarts.lastIndex) dataEndDay + extraDays else observedEnd
        val regimeObservations = observations.filter { it.dayNumber in regimeStart..observedEnd }
        val change = detected.getOrNull(index - 1)
        val continuousInitialState = change?.let {
            val previous = states.last()
            KalmanInitialState(previous.phase + previous.drift, it.newDrift)
        }
        states += fitUnwrappedKalmanTrend(
            observations = regimeObservations,
            firstDay = regimeStart,
            lastDay = stateEnd,
            config = config,
            initialState = continuousInitialState,
        )
    }
    val firstStateDay = states.first().dayNumber
    val durationsByDay = smoothDurations(
        observations = anchors.map { DurationObservation(it.dayNumber, it.record.durationHours) },
        targetDays = firstStateDay..states.last().dayNumber,
        config = config.durationSmoothing,
    ).withIndex().associate { (index, duration) -> firstStateDay + index to duration }
    return KalmanSegment(
        firstDay = anchors.first().dayNumber,
        dataEndDay = dataEndDay,
        anchorCount = anchors.size,
        changePoints = detected.map { point ->
            KalmanChangePoint(
                date = firstDate.plusDays(point.dayNumber.toLong()),
                previousDrift = point.previousDrift,
                newDrift = point.newDrift,
                evidenceRatio = point.evidenceRatio,
                confirmationDate = firstDate.plusDays(point.confirmationDayNumber.toLong()),
            )
        },
        days = states.map { state ->
            val isForecast = state.dayNumber > dataEndDay
            val confidenceScore = if (isForecast) {
                max(0.1, 0.5 * exp(-0.1 * (state.dayNumber - dataEndDay)))
            } else {
                1.0 / (1.0 + state.phaseVariance)
            }
            val tau = (24.0 + state.drift).coerceIn(KALMAN_TAU_MIN, KALMAN_TAU_MAX)
            // The filter's phase is intentionally unwrapped, but CircadianDay
            // is a clock-time window relative to its own [date].  Passing the
            // unwrapped value here makes the actogram treat one night as a
            // window spanning months of calendar rows.
            val midpointHour = normalizeClockHour(state.phase)
            val duration = durationsByDay.getValue(state.dayNumber)
            CircadianDay(
                date = firstDate.plusDays(state.dayNumber.toLong()),
                nightStartHour = midpointHour - duration / 2.0,
                nightEndHour = midpointHour + duration / 2.0,
                confidenceScore = confidenceScore,
                confidence = when {
                    confidenceScore >= 0.6 -> CircadianConfidence.HIGH
                    confidenceScore >= 0.3 -> CircadianConfidence.MEDIUM
                    else -> CircadianConfidence.LOW
                },
                localTau = tau,
                localDrift = tau - 24.0,
                anchorSleep = anchors.find { it.dayNumber == state.dayNumber }?.record,
                isForecast = isForecast,
                isGap = false,
            )
        },
    )
}

private data class KalmanSegment(
    val firstDay: Int,
    val dataEndDay: Int,
    val anchorCount: Int,
    val changePoints: List<KalmanChangePoint>,
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

private fun emptyKalmanAnalysis() = KalmanAnalysis(
    globalTau = 24.0,
    globalDailyDrift = 0.0,
    days = emptyList(),
    algorithmId = KALMAN_ALGORITHM_ID,
    tau = 24.0,
    dailyDrift = 0.0,
    rSquared = 0.0,
    anchorCount = 0,
    changePoints = emptyList(),
)
