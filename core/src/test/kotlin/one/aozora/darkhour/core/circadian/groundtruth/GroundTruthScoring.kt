package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sign

/** Minimal common output so existing and experimental estimators compare fairly. */
data class GroundTruthPredictionDay(
    val date: LocalDate,
    val nightStartHour: Double,
    val nightEndHour: Double,
    val localTau: Double = 24.0,
    val localDrift: Double = 0.0,
    val isForecast: Boolean = false,
    val isGap: Boolean = false,
)

interface GroundTruthAlgorithm {
    val id: String
    fun analyze(records: List<SleepRecord>): List<GroundTruthPredictionDay>
}

object CsfGroundTruthAlgorithm : GroundTruthAlgorithm {
    override val id = CircadianAlgorithmRegistry.CSF_ID

    override fun analyze(records: List<SleepRecord>): List<GroundTruthPredictionDay> =
        CircadianAlgorithmRegistry.analyze(records, algorithmId = id).toGroundTruthPrediction()
}

fun CircadianAnalysis.toGroundTruthPrediction(): List<GroundTruthPredictionDay> = days.map(CircadianDay::toGroundTruthPrediction)

fun CircadianDay.toGroundTruthPrediction() = GroundTruthPredictionDay(
    date = date,
    nightStartHour = nightStartHour,
    nightEndHour = nightEndHour,
    localTau = localTau,
    localDrift = localDrift,
    isForecast = isForecast,
    isGap = isGap,
)

data class GroundTruthScore(
    val pairedDays: Int,
    val meanAbsolutePhaseErrorHours: Double,
    val medianAbsolutePhaseErrorHours: Double,
    val p90AbsolutePhaseErrorHours: Double,
    val signedMeanPhaseErrorHours: Double,
    val driftDirectionAgreement: Int,
    val driftDirectionDisagreement: Int,
    val stalledDriftDays: Int,
    val driftComparableDays: Int,
    val algorithmTau: Double,
    val manualTau: Double,
    val tauDeltaMinutes: Double,
    val maxWindowTauDeltaMinutes: Double,
    val divergenceStreaks: List<DivergenceStreak>,
    val driftPenalty: DriftPenalty,
    val phaseConsistencyP90Hours: Double,
    val firstSevenDayMeanErrorHours: Double?,
    val lastSevenDayMeanErrorHours: Double?,
    val regimeTransitions: GroundTruthRegimeTransitionScore,
)

data class DivergenceStreak(
    val start: LocalDate,
    val end: LocalDate,
    val days: Int,
    val peakErrorHours: Double,
    val meanSignedErrorHours: Double,
)

data class DriftPenalty(
    val total: Double,
    val days: Int,
    val fraction: Double,
)

private data class ScoredPair(
    val date: LocalDate,
    val absoluteError: Double,
    val signedError: Double,
    val algorithmDrift: Double?,
    val manualDrift: Double?,
)

/**
 * Kotlin port of the JavaScript ground-truth score.  It intentionally scores
 * the manually drawn overlay, not individual sleep episodes, and reports
 * metrics rather than declaring one algorithm universally correct.
 */
fun scoreAgainstGroundTruth(
    prediction: List<GroundTruthPredictionDay>,
    dataset: GroundTruthDataset,
): GroundTruthScore {
    val expected = dataset.overlay.sortedBy { it.date }
    val usablePrediction = prediction.filterNot { it.isForecast || it.isGap }.sortedBy { it.date }
    val predictionByDate = usablePrediction.associateBy { it.date }
    val algorithmDrift = driftByDate(usablePrediction.map(::asTimingDay))
    val manualDrift = driftByDate(expected.map(::asTimingDay))
    val pairs = expected.mapNotNull { manual ->
        predictionByDate[manual.date]?.let { actual ->
            val error = signedCircularError(midpoint(actual), midpoint(manual))
            ScoredPair(
                date = manual.date,
                absoluteError = abs(error),
                signedError = error,
                algorithmDrift = algorithmDrift[manual.date],
                manualDrift = manualDrift[manual.date],
            )
        }
    }
    require(pairs.isNotEmpty()) { "No predicted days overlap manual overlay for ${dataset.id}" }

    val sortedErrors = pairs.map(ScoredPair::absoluteError).sorted()
    val comparable = pairs.filter { it.algorithmDrift != null && it.manualDrift != null }
    val direction = comparable.fold(DriftDirectionCounts()) { counts, pair -> counts.add(pair.algorithmDrift!!, pair.manualDrift!!) }
    val expectedByDate = expected.associateBy { it.date }
    val windows = pairs.chunked(90).filter { it.size >= 10 }.map { window ->
        abs(
            (tauFromTimingDays(window.map { asTimingDay(predictionByDate.getValue(it.date)) }) -
                tauFromTimingDays(window.map { asTimingDay(expectedByDate.getValue(it.date)) })) * 60.0,
        )
    }
    val consistency = usablePrediction.zipWithNext().map { (previous, current) ->
        abs(signedCircularError(midpoint(current), midpoint(previous)) - (previous.localTau - 24.0))
    }.sorted()

    return GroundTruthScore(
        pairedDays = pairs.size,
        meanAbsolutePhaseErrorHours = sortedErrors.average(),
        medianAbsolutePhaseErrorHours = percentile(sortedErrors, 0.5),
        p90AbsolutePhaseErrorHours = percentile(sortedErrors, 0.9),
        signedMeanPhaseErrorHours = pairs.map(ScoredPair::signedError).average(),
        driftDirectionAgreement = direction.agree,
        driftDirectionDisagreement = direction.disagree,
        stalledDriftDays = direction.stalled,
        driftComparableDays = comparable.size,
        algorithmTau = tauFromTimingDays(usablePrediction.map(::asTimingDay)),
        manualTau = tauFromTimingDays(expected.map(::asTimingDay)),
        tauDeltaMinutes = (tauFromTimingDays(usablePrediction.map(::asTimingDay)) -
            tauFromTimingDays(expected.map(::asTimingDay))) * 60.0,
        maxWindowTauDeltaMinutes = windows.maxOrNull() ?: 0.0,
        divergenceStreaks = divergenceStreaks(pairs),
        driftPenalty = driftPenalty(usablePrediction),
        phaseConsistencyP90Hours = consistency.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.9) } ?: 0.0,
        firstSevenDayMeanErrorHours = pairs.take(7).takeIf { it.size == 7 }?.map(ScoredPair::absoluteError)?.average(),
        lastSevenDayMeanErrorHours = pairs.takeLast(7).takeIf { it.size == 7 }?.map(ScoredPair::absoluteError)?.average(),
        regimeTransitions = scoreRegimeTransitions(prediction, dataset),
    )
}

private data class TimingDay(val date: LocalDate, val nightStartHour: Double, val nightEndHour: Double)

private fun asTimingDay(day: GroundTruthPredictionDay) = TimingDay(day.date, day.nightStartHour, day.nightEndHour)
private fun asTimingDay(day: GroundTruthOverlayDay) = TimingDay(day.date, day.nightStartHour, day.nightEndHour)
private fun midpoint(day: TimingDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun midpoint(day: GroundTruthPredictionDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun midpoint(day: GroundTruthOverlayDay) = (day.nightStartHour + day.nightEndHour) / 2.0

private fun signedCircularError(actual: Double, expected: Double): Double {
    var result = normalizeHour(actual) - normalizeHour(expected)
    if (result > 12.0) result -= 24.0
    if (result <= -12.0) result += 24.0
    return result
}

private fun normalizeHour(hour: Double): Double = ((hour % 24.0) + 24.0) % 24.0

private fun driftByDate(days: List<TimingDay>): Map<LocalDate, Double> = buildMap {
    days.zipWithNext().forEach { (previous, current) ->
        put(current.date, unwrappedStep(midpoint(current), midpoint(previous)))
    }
}

private fun tauFromTimingDays(days: List<TimingDay>): Double = tauFromShift(cumulativeShift(days), days.size)

private fun cumulativeShift(days: List<TimingDay>): Double = days.zipWithNext().sumOf { (previous, current) ->
    unwrappedStep(midpoint(current), midpoint(previous))
}

/** Matches the JS scorer's midpoint unwrapping, including its -12 h tie case. */
private fun unwrappedStep(current: Double, previous: Double): Double {
    var unwrapped = current
    while (unwrapped - previous > 12.0) unwrapped -= 24.0
    while (previous - unwrapped > 12.0) unwrapped += 24.0
    return unwrapped - previous
}

private fun tauFromShift(shiftHours: Double, dayCount: Int): Double {
    val revolutions = abs(shiftHours / 24.0)
    if (dayCount < 2 || revolutions < 0.1) return 24.0
    return (24.0 * dayCount) / (dayCount - sign(shiftHours) * revolutions)
}

private fun percentile(sorted: List<Double>, percentile: Double): Double = sorted[(sorted.size * percentile).toInt()]

private data class DriftDirectionCounts(val agree: Int = 0, val disagree: Int = 0, val stalled: Int = 0) {
    fun add(algorithm: Double, manual: Double): DriftDirectionCounts {
        val algorithmSignificant = abs(algorithm) >= 0.1
        val manualSignificant = abs(manual) >= 0.1
        return when {
            !algorithmSignificant && manualSignificant -> copy(stalled = stalled + 1)
            algorithmSignificant && manualSignificant && sign(algorithm) != sign(manual) -> copy(disagree = disagree + 1)
            else -> copy(agree = agree + 1)
        }
    }
}

private fun divergenceStreaks(pairs: List<ScoredPair>): List<DivergenceStreak> {
    val streaks = mutableListOf<DivergenceStreak>()
    var start = 0
    while (start < pairs.size) {
        if (pairs[start].absoluteError <= 2.0) {
            start++
            continue
        }
        val endExclusive = generateSequence(start) { it + 1 }
            .takeWhile { it < pairs.size && pairs[it].absoluteError > 2.0 }
            .last() + 1
        val run = pairs.subList(start, endExclusive)
        if (run.size >= 3) {
            streaks += DivergenceStreak(
                start = run.first().date,
                end = run.last().date,
                days = run.size,
                peakErrorHours = run.maxOf(ScoredPair::absoluteError),
                meanSignedErrorHours = run.map(ScoredPair::signedError).average(),
            )
        }
        start = endExclusive
    }
    return streaks
}

private fun driftPenalty(days: List<GroundTruthPredictionDay>): DriftPenalty {
    var total = 0.0
    var penaltyDays = 0
    var consecutive = 0
    for (day in days) {
        val drift = day.localDrift
        val dayPenalty = when {
            drift in -1.5..< -0.5 -> (abs(drift) - 0.5) / 1.0
            drift in 2.0..3.0 -> (drift - 2.0) / 1.0
            else -> 0.0
        }
        if (dayPenalty > 0.0) {
            penaltyDays++
            consecutive++
            total += dayPenalty * consecutive
        } else {
            consecutive = 0
        }
    }
    return DriftPenalty(total, penaltyDays, penaltyDays.toDouble() / days.size.coerceAtLeast(1))
}
