package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.TauSegment
import one.aozora.darkhour.core.circadian.csf.computeTrueMidpoint
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
import one.aozora.darkhour.core.circadian.kalman.analyzeCircadianKalman
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces a treatment-like change point: a 25-hour free-running trajectory
 * becomes 24-hour entrained on 2026-07-01 while sleep itself remains noisy.
 */
class AbruptEntrainmentRegressionTest {
    @Test
    fun syntheticFixtureStopsDelayingImmediatelyWithoutAPhaseJump() {
        val fixture = abruptEntrainmentFixture()

        assertEquals(1.0, trueSlope(fixture.options, TREATMENT_DAY - 14 until TREATMENT_DAY), 1e-9)
        assertEquals(0.0, trueSlope(fixture.options, TREATMENT_DAY until TOTAL_DAYS), 1e-9)
        assertEquals(
            computeTrueMidpoint(TREATMENT_DAY, fixture.options),
            computeTrueMidpoint(TREATMENT_DAY + 1, fixture.options),
            1e-9,
        )
        assertTrue(fixture.records.all { it.dateOfSleep <= LAST_OBSERVED_DATE })
    }

    @Test
    fun reportsCurrentEstimatorBehaviorAcrossTheAbruptTransition() {
        val fixture = abruptEntrainmentFixture()

        CircadianAlgorithmRegistry.algorithms.forEach { algorithm ->
            val behavior = transitionBehavior(algorithm.id, fixture)
            println(
                "TRANSITION\t${algorithm.id}" +
                    "\tfar-revision-mean=${format(behavior.farRevisionMeanHours)}h" +
                    "\tnear-revision-mean=${format(behavior.nearRevisionMeanHours)}h" +
                    "\tnear-revision-max=${format(behavior.nearRevisionMaxHours)}h" +
                    "\tnear-revision-bias=${format(behavior.nearRevisionSignedMeanHours)}h" +
                    "\tpost-slope=${format(behavior.postPhaseSlopeHoursPerDay)}h/d" +
                    "\tpost-local-drift=${format(behavior.postLocalDriftHoursPerDay)}h/d" +
                    "\tpost-phase-mae=${format(behavior.postPhaseMaeHours)}h",
            )
            assertTrue(behavior.values().all(Double::isFinite))
        }
    }

    @Test
    fun kalmanPreservesPreTreatmentHistoryAndRecognizesRapidEntrainment() {
        val fixture = abruptEntrainmentFixture()

        val behavior = transitionBehavior(CircadianAlgorithmRegistry.KALMAN_ID, fixture)
        val direct = analyzeCircadianKalman(fixture.records, config = productionKalmanConfig())
        val change = direct.changePoints.single()
        val revisionBeforeBoundary = maximumRevisionBeforeBoundary(fixture, change.date)
        assertTrue(
            "boundary ${change.date} was not within two days of $TREATMENT_DATE",
            abs(Duration.between(TREATMENT_DATE.atStartOfDay(), change.date.atStartOfDay()).toDays()) <= 2,
        )
        assertTrue(
            "confirmation ${change.confirmationDate} took more than seven qualifying nights",
            Duration.between(change.date.atStartOfDay(), change.confirmationDate.atStartOfDay()).toDays() <= 6,
        )
        assertTrue(
            "confirmation ${change.confirmationDate} exceeded the 14-day window",
            Duration.between(change.date.atStartOfDay(), change.confirmationDate.atStartOfDay()).toDays() < 14,
        )
        assertTrue(
            "Kalman rewrote history before inferred boundary $change by $revisionBeforeBoundary h",
            revisionBeforeBoundary < 0.5,
        )
        assertTrue(
            "Kalman retained a post-treatment slope of ${behavior.postPhaseSlopeHoursPerDay} h/day",
            abs(behavior.postPhaseSlopeHoursPerDay) < 0.25,
        )
        assertTrue(
            "Kalman retained local drift of ${behavior.postLocalDriftHoursPerDay} h/day",
            abs(behavior.postLocalDriftHoursPerDay) < 0.25,
        )
    }
}

private fun maximumRevisionBeforeBoundary(fixture: AbruptEntrainmentFixture, boundary: LocalDate): Double {
    val before = CircadianAlgorithmRegistry.analyze(
        records = fixture.records.filter { it.dateOfSleep < TREATMENT_DATE },
        algorithmId = CircadianAlgorithmRegistry.KALMAN_ID,
    ).days.filterNot { it.isGap || it.isForecast }.associateBy(CircadianDay::date)
    val after = CircadianAlgorithmRegistry.analyze(
        records = fixture.records,
        algorithmId = CircadianAlgorithmRegistry.KALMAN_ID,
    ).days.filterNot { it.isGap || it.isForecast }.associateBy(CircadianDay::date)
    return before.keys.asSequence()
        .filter { it < boundary }
        .mapNotNull { date -> after[date]?.let { signedCircularDifference(midpoint(it), midpoint(before.getValue(date))) } }
        .maxOf(::abs)
}

private data class AbruptEntrainmentFixture(
    val records: List<SleepRecord>,
    val options: SyntheticOptions,
)

private data class TransitionBehavior(
    val farRevisionMeanHours: Double,
    val nearRevisionMeanHours: Double,
    val nearRevisionMaxHours: Double,
    val nearRevisionSignedMeanHours: Double,
    val postPhaseSlopeHoursPerDay: Double,
    val postLocalDriftHoursPerDay: Double,
    val postPhaseMaeHours: Double,
) {
    fun values() = listOf(
        farRevisionMeanHours,
        nearRevisionMeanHours,
        nearRevisionMaxHours,
        nearRevisionSignedMeanHours,
        postPhaseSlopeHoursPerDay,
        postLocalDriftHoursPerDay,
        postPhaseMaeHours,
    )
}

private fun abruptEntrainmentFixture(): AbruptEntrainmentFixture {
    val options = SyntheticOptions(
        days = TOTAL_DAYS,
        noise = 0.15,
        seed = 20260701,
        startDate = TREATMENT_DATE.minusDays(TREATMENT_DAY.toLong()),
        startMidpoint = TREATMENT_MIDPOINT - TREATMENT_DAY,
        tauSegments = listOf(
            TauSegment(untilDay = TREATMENT_DAY, tau = 25.0),
            TauSegment(untilDay = TOTAL_DAYS, tau = 24.0),
        ),
    )
    val records = generateSyntheticRecords(options).map { record ->
        val day = Duration.between(options.startDate.atStartOfDay(), record.dateOfSleep.atStartOfDay()).toDays().toInt()
        if (day < TREATMENT_DAY) return@map record
        val offsetMillis = (POST_TREATMENT_MIDPOINT_OFFSETS[day - TREATMENT_DAY] * 3_600_000.0).toLong()
        record.copy(
            startTime = record.startTime.plusMillis(offsetMillis),
            endTime = record.endTime.plusMillis(offsetMillis),
        )
    }
    return AbruptEntrainmentFixture(records, options)
}

private fun transitionBehavior(algorithmId: String, fixture: AbruptEntrainmentFixture): TransitionBehavior {
    val before = CircadianAlgorithmRegistry.analyze(
        records = fixture.records.filter { it.dateOfSleep < TREATMENT_DATE },
        algorithmId = algorithmId,
    )
    val after = CircadianAlgorithmRegistry.analyze(fixture.records, algorithmId = algorithmId)
    val beforeByDate = before.days.filterNot { it.isGap || it.isForecast }.associateBy(CircadianDay::date)
    val afterByDate = after.days.filterNot { it.isGap || it.isForecast }.associateBy(CircadianDay::date)
    fun revisions(dayOffsets: IntRange): List<Double> = dayOffsets.mapNotNull { offset ->
        val date = TREATMENT_DATE.plusDays(offset.toLong())
        val old = beforeByDate[date] ?: return@mapNotNull null
        val new = afterByDate[date] ?: return@mapNotNull null
        signedCircularDifference(midpoint(new), midpoint(old))
    }.also { require(it.isNotEmpty()) }
    val farRevisions = revisions(-42..-15)
    val nearRevisions = revisions(-14..-1)

    val postDays = after.days.filter {
        !it.isGap && !it.isForecast && it.date in TREATMENT_DATE..LAST_OBSERVED_DATE
    }.sortedBy(CircadianDay::date)
    val unwrappedPostPhase = unwrap(postDays.map(::midpoint))
    val expectedMidpoints = postDays.map { day ->
        val index = Duration.between(fixture.options.startDate.atStartOfDay(), day.date.atStartOfDay()).toDays().toInt()
        computeTrueMidpoint(index, fixture.options)
    }

    return TransitionBehavior(
        farRevisionMeanHours = farRevisions.map(::abs).average(),
        nearRevisionMeanHours = nearRevisions.map(::abs).average(),
        nearRevisionMaxHours = nearRevisions.maxOf(::abs),
        nearRevisionSignedMeanHours = nearRevisions.average(),
        postPhaseSlopeHoursPerDay = linearSlope(unwrappedPostPhase),
        postLocalDriftHoursPerDay = postDays.takeLast(5).map(CircadianDay::localDrift).average(),
        postPhaseMaeHours = postDays.zip(expectedMidpoints).map { (day, expected) ->
            abs(signedCircularDifference(midpoint(day), expected))
        }.average(),
    )
}

private fun trueSlope(options: SyntheticOptions, days: IntRange): Double =
    linearSlope(days.map { computeTrueMidpoint(it, options) })

private fun linearSlope(values: List<Double>): Double {
    require(values.size >= 2)
    val meanX = (values.lastIndex) / 2.0
    val meanY = values.average()
    val numerator = values.indices.sumOf { index -> (index - meanX) * (values[index] - meanY) }
    val denominator = values.indices.sumOf { index -> (index - meanX) * (index - meanX) }
    return numerator / denominator
}

private fun unwrap(clockHours: List<Double>): List<Double> {
    if (clockHours.isEmpty()) return emptyList()
    val result = mutableListOf(normalizeHour(clockHours.first()))
    clockHours.drop(1).forEach { hour ->
        val normalized = normalizeHour(hour)
        result += normalized + round((result.last() - normalized) / 24.0) * 24.0
    }
    return result
}

private fun midpoint(day: CircadianDay) = (day.nightStartHour + day.nightEndHour) / 2.0
private fun normalizeHour(hour: Double) = ((hour % 24.0) + 24.0) % 24.0

private fun signedCircularDifference(actual: Double, expected: Double): Double {
    var difference = normalizeHour(actual) - normalizeHour(expected)
    if (difference > 12.0) difference -= 24.0
    if (difference <= -12.0) difference += 24.0
    return difference
}

private fun format(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

private fun productionKalmanConfig() = KalmanConfig(
    driftPrior = 1.0,
    processPhaseVariance = 0.42,
    processDriftVariance = 0.0001,
    measurementVarianceAtUnitWeight = 8.0,
)

private const val TREATMENT_DAY = 90
private const val TOTAL_DAYS = 101
private const val TREATMENT_MIDPOINT = 4.0
private val TREATMENT_DATE = LocalDate.parse("2026-07-01")
private val LAST_OBSERVED_DATE = TREATMENT_DATE.plusDays((TOTAL_DAYS - TREATMENT_DAY - 1).toLong())
private val POST_TREATMENT_MIDPOINT_OFFSETS = listOf(0.0, -0.2, 0.25, -0.1, 0.15, -0.25, 0.1, 0.2, -0.15, 0.15, -0.1)
