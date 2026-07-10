package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round

internal data class CausalForecastWindowScore(
    val datasetId: String,
    val cutoff: LocalDate,
    val horizonDays: Int,
    val circularPhaseMaeHours: Double,
    val unwrappedPhaseMaeHours: Double,
    val endpointPhaseErrorHours: Double,
    val cumulativeMovementErrorHours: Double,
    val tauDeltaMinutesPerDay: Double,
)

internal data class CausalForecastSummary(
    val windows: Int,
    val circularPhaseMaeHours: Double,
    val unwrappedPhaseMaeHours: Double,
    val endpointP90Hours: Double,
    val movementP90Hours: Double,
    val absoluteTauDeltaP90MinutesPerDay: Double,
    val signedMeanTauDeltaMinutesPerDay: Double,
    val halfCycleErrorFraction: Double,
    val fullCycleErrorFraction: Double,
)

internal fun causalCutoffs(
    dataset: GroundTruthDataset,
    historyDays: Int,
    horizonDays: Int,
    spacingDays: Int,
): List<LocalDate> {
    require(historyDays >= 1)
    require(horizonDays >= 2)
    require(spacingDays >= 1)
    val overlayDates = dataset.overlay.map(GroundTruthOverlayDay::date).toSet()
    val firstOverlayDate = overlayDates.minOrNull() ?: return emptyList()
    val lastOverlayDate = overlayDates.maxOrNull() ?: return emptyList()
    val lastCutoff = lastOverlayDate.minusDays(horizonDays.toLong())
    val cutoffs = mutableListOf<LocalDate>()
    var cutoff = firstOverlayDate.plusDays(historyDays.toLong())
    while (cutoff <= lastCutoff) {
        val history = historicalRecords(dataset.records, cutoff)
        val historySpan = history.minOfOrNull(SleepRecord::dateOfSleep)?.let {
            ChronoUnit.DAYS.between(it, cutoff)
        } ?: 0
        val daysSinceLastRecord = history.maxOfOrNull(SleepRecord::dateOfSleep)?.let {
            ChronoUnit.DAYS.between(it, cutoff)
        } ?: Long.MAX_VALUE
        val completeExpectedHorizon = (1..horizonDays).all { cutoff.plusDays(it.toLong()) in overlayDates }
        if (
            historySpan >= historyDays.toLong() &&
            daysSinceLastRecord <= MAX_RECENT_RECORD_AGE_DAYS &&
            completeExpectedHorizon
        ) {
            cutoffs += cutoff
        }
        cutoff = cutoff.plusDays(spacingDays.toLong())
    }
    return cutoffs
}

internal fun historicalRecords(records: List<SleepRecord>, cutoff: LocalDate): List<SleepRecord> =
    records.filter { it.dateOfSleep <= cutoff }

internal fun scoreCausalForecastWindow(
    datasetId: String,
    cutoff: LocalDate,
    horizonDays: Int,
    prediction: List<GroundTruthPredictionDay>,
    expectedOverlay: List<GroundTruthOverlayDay>,
): CausalForecastWindowScore? {
    require(horizonDays >= 2)
    val expectedByDate = expectedOverlay.associateBy(GroundTruthOverlayDay::date)
    val actualByDate = prediction.associateBy(GroundTruthPredictionDay::date)
    val dates = (1..horizonDays).map { cutoff.plusDays(it.toLong()) }
    val expected = dates.map { expectedByDate[it] ?: return null }
    val actual = dates.map { actualByDate[it] ?: return null }

    val expectedMidpoints = expected.map(::midpoint)
    val actualClockMidpoints = actual.map { normalizeHour(midpoint(it)) }
    val actualUnwrapped = mutableListOf(
        actualClockMidpoints.first() + round((expectedMidpoints.first() - actualClockMidpoints.first()) / 24.0) * 24.0,
    )
    for (index in 1 until actualClockMidpoints.size) {
        val clockHour = actualClockMidpoints[index]
        actualUnwrapped += clockHour + round((actualUnwrapped.last() - clockHour) / 24.0) * 24.0
    }

    val circularErrors = actualClockMidpoints.zip(expectedMidpoints).map { (actualHour, expectedHour) ->
        abs(signedCircularError(actualHour, expectedHour))
    }
    val unwrappedErrors = actualUnwrapped.zip(expectedMidpoints).map { (actualHour, expectedHour) ->
        actualHour - expectedHour
    }
    val actualMovement = actualUnwrapped.last() - actualUnwrapped.first()
    val expectedMovement = expectedMidpoints.last() - expectedMidpoints.first()
    val movementError = actualMovement - expectedMovement

    return CausalForecastWindowScore(
        datasetId = datasetId,
        cutoff = cutoff,
        horizonDays = horizonDays,
        circularPhaseMaeHours = circularErrors.average(),
        unwrappedPhaseMaeHours = unwrappedErrors.map(::abs).average(),
        endpointPhaseErrorHours = unwrappedErrors.last(),
        cumulativeMovementErrorHours = movementError,
        tauDeltaMinutesPerDay = movementError / (horizonDays - 1) * 60.0,
    )
}

internal fun summarizeCausalForecasts(scores: List<CausalForecastWindowScore>): CausalForecastSummary {
    require(scores.isNotEmpty())
    val absoluteEndpoints = scores.map { abs(it.endpointPhaseErrorHours) }.sorted()
    val absoluteMovement = scores.map { abs(it.cumulativeMovementErrorHours) }.sorted()
    val absoluteTauDelta = scores.map { abs(it.tauDeltaMinutesPerDay) }.sorted()
    return CausalForecastSummary(
        windows = scores.size,
        circularPhaseMaeHours = scores.map(CausalForecastWindowScore::circularPhaseMaeHours).average(),
        unwrappedPhaseMaeHours = scores.map(CausalForecastWindowScore::unwrappedPhaseMaeHours).average(),
        endpointP90Hours = percentile(absoluteEndpoints, 0.90),
        movementP90Hours = percentile(absoluteMovement, 0.90),
        absoluteTauDeltaP90MinutesPerDay = percentile(absoluteTauDelta, 0.90),
        signedMeanTauDeltaMinutesPerDay = scores.map(CausalForecastWindowScore::tauDeltaMinutesPerDay).average(),
        halfCycleErrorFraction = scores.count { abs(it.cumulativeMovementErrorHours) >= 12.0 }.toDouble() / scores.size,
        fullCycleErrorFraction = scores.count { abs(it.cumulativeMovementErrorHours) >= 24.0 }.toDouble() / scores.size,
    )
}

private fun midpoint(day: GroundTruthPredictionDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun midpoint(day: GroundTruthOverlayDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun normalizeHour(hour: Double): Double = ((hour % 24.0) + 24.0) % 24.0

private fun signedCircularError(actual: Double, expected: Double): Double {
    var error = normalizeHour(actual) - normalizeHour(expected)
    if (error > 12.0) error -= 24.0
    if (error <= -12.0) error += 24.0
    return error
}

private fun percentile(sorted: List<Double>, fraction: Double): Double =
    sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.lastIndex)]

private const val MAX_RECENT_RECORD_AGE_DAYS = 7L
