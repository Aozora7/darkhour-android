package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.TauSegment
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.round
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the causal edge response seen when a long free-running history is
 * followed by a short, noisy but clock-stable run. Each prefix represents one
 * more record becoming available; private fixtures are deliberately not used.
 */
class RecentTrendForecastRegressionTest {
    @Test
    fun allAlgorithmsStraightenForecastAsStableRecordsAccumulate() {
        val fixture = recentEntrainmentFixture()
        val failures = mutableListOf<String>()

        CircadianAlgorithmRegistry.algorithms.forEach { algorithm ->
            val response = (0..STABLE_DAYS).map { injectedRecords ->
                val lastIncludedDay = FREE_RUNNING_DAYS + injectedRecords
                val prefix = fixture.filter { record ->
                    record.dateOfSleep < START_DATE.plusDays(lastIncludedDay.toLong())
                }
                forecastSlope(
                    CircadianAlgorithmRegistry.analyze(
                        records = prefix,
                        extraDays = FORECAST_DAYS,
                        algorithmId = algorithm.id,
                        overrides = RECENT_TREND_KALMAN_OVERRIDES,
                    ),
                )
            }
            val firstResponsivePrefix = response.indexOfFirst { abs(it) <= RESPONSIVE_DRIFT }
            println(
                "RECENT_TREND\t${algorithm.id}" +
                    "\tinitial=${response.first().format()}h/d" +
                    "\tfinal=${response.last().format()}h/d" +
                    "\tresponsive-prefix=$firstResponsivePrefix" +
                    "\tresponse=${response.joinToString(",") { it.format() }}",
            )

            if (response.first() <= RESPONSIVE_DRIFT) {
                failures += "${algorithm.id} did not begin with a free-running forecast: ${response.first()}"
            }
            if (firstResponsivePrefix !in 1..STABLE_DAYS) {
                failures += "${algorithm.id} still forecast ${response.last()} h/day after $STABLE_DAYS stable " +
                    "records; response=${response.joinToString()}"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun kalmanAlgorithmsPlaceNightNearFourteenStableInjectedSleeps() {
        val records = exactClockEntrainmentFixture()
        val expectedMidpoint = 2.95 // midpoint of 23:27-06:27
        val failures = mutableListOf<String>()

        CircadianAlgorithmRegistry.algorithms.forEach { algorithm ->
            val analysis = CircadianAlgorithmRegistry.analyze(
                records,
                extraDays = FORECAST_DAYS,
                algorithmId = algorithm.id,
                overrides = RECENT_TREND_KALMAN_OVERRIDES,
            )
            val terminal = analysis.days.single { it.date == records.last().dateOfSleep }
            val actualMidpoint = normalizeRecentTrendHour((terminal.nightStartHour + terminal.nightEndHour) / 2.0)
            val error = abs(circularRecentTrendDifference(actualMidpoint, expectedMidpoint))
            println(
                "RECENT_PHASE\t${algorithm.id}\tmidpoint=${actualMidpoint.format()}" +
                    "\twindow=${normalizeRecentTrendHour(terminal.nightStartHour).format()}-" +
                    normalizeRecentTrendHour(terminal.nightEndHour).format() +
                    "\terror=${error.format()}h",
            )
            // CSF is retained in the diagnostic output as a comparator; this
            // regression protects the edge estimator shared by both Kalmans.
            if (algorithm.id != CircadianAlgorithmRegistry.CSF_ID &&
                error > MAX_TERMINAL_PHASE_ERROR_HOURS
            ) {
                failures += "${algorithm.id} midpoint $actualMidpoint was $error h from $expectedMidpoint"
            }
        }

        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun noisyFreeRunningTailIsNotMistakenForEntrainment() {
        val failures = mutableListOf<String>()
        repeat(8) { seed ->
            val records = generateSyntheticRecords(
                SyntheticOptions(
                    days = 140,
                    tau = 25.0,
                    noise = 0.65,
                    gapFraction = 0.12,
                    outlierFraction = 0.04,
                    outlierOffset = 5.0,
                    quality = 1.0,
                    seed = 700 + seed,
                    startDate = START_DATE,
                ),
            )
            CircadianAlgorithmRegistry.algorithms.forEach { algorithm ->
                val slope = forecastSlope(
                    CircadianAlgorithmRegistry.analyze(
                        records,
                        extraDays = FORECAST_DAYS,
                        algorithmId = algorithm.id,
                        overrides = RECENT_TREND_KALMAN_OVERRIDES,
                    ),
                )
                if (abs(slope - 1.0) > STABLE_N24_TOLERANCE) {
                    failures += "${algorithm.id}/$seed forecast $slope h/day"
                }
            }
        }
        assertTrue("false flattening: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test
    fun productionKalmanRespondsFasterThanCsfAcrossNoisyTransitions() {
        val csfLatencies = mutableListOf<Int>()
        val kalmanLatencies = mutableListOf<Int>()
        repeat(NOISY_TRANSITION_SEEDS) { seed ->
            val fixture = generateSyntheticRecords(
                SyntheticOptions(
                    days = FREE_RUNNING_DAYS + NOISY_STABLE_DAYS,
                    noise = 0.55,
                    seed = 900 + seed,
                    quality = 1.0,
                    startDate = START_DATE,
                    tauSegments = listOf(
                        TauSegment(untilDay = FREE_RUNNING_DAYS, tau = 25.0),
                        TauSegment(untilDay = FREE_RUNNING_DAYS + NOISY_STABLE_DAYS, tau = 24.0),
                    ),
                ),
            )
            csfLatencies += responsivePrefix(fixture, CircadianAlgorithmRegistry.CSF_ID, NOISY_STABLE_DAYS)
            kalmanLatencies += responsivePrefix(fixture, CircadianAlgorithmRegistry.KALMAN_ID, NOISY_STABLE_DAYS)
        }

        val csfMean = csfLatencies.average()
        val kalmanMean = kalmanLatencies.average()
        println("RECENT_TREND_NOISY\tcsf=$csfLatencies\tkalman=$kalmanLatencies")
        assertTrue(
            "Kalman mean response $kalmanMean days did not lead CSF $csfMean days",
            kalmanMean <= csfMean - MIN_MEAN_RESPONSE_LEAD_DAYS,
        )
    }

    @Test
    fun isolatedEndpointOutlierDoesNotYankProductionKalmanForecast() {
        val records = generateSyntheticRecords(
            SyntheticOptions(
                days = 120,
                tau = 25.0,
                noise = 0.25,
                quality = 1.0,
                seed = 20260714,
                startDate = START_DATE,
            ),
        )
        val baseline = productionKalmanAnalysis(records)
        val shifted = records.dropLast(1) + records.last().let { record ->
            record.copy(
                startTime = record.startTime.plus(Duration.ofHours(6)),
                endTime = record.endTime.plus(Duration.ofHours(6)),
            )
        }
        val withOutlier = productionKalmanAnalysis(shifted)

        assertTrue(
            "endpoint outlier changed forecast from ${forecastSlope(baseline)} to " +
                "${forecastSlope(withOutlier)} h/day",
            abs(forecastSlope(withOutlier) - forecastSlope(baseline)) < ENDPOINT_OUTLIER_TOLERANCE,
        )
        val baselinePhase = terminalObservedMidpoint(baseline)
        val outlierPhase = terminalObservedMidpoint(withOutlier)
        assertTrue(
            "endpoint outlier changed phase from $baselinePhase to $outlierPhase",
            abs(circularRecentTrendDifference(outlierPhase, baselinePhase)) < ENDPOINT_PHASE_OUTLIER_TOLERANCE,
        )
    }
}

private fun responsivePrefix(records: List<SleepRecord>, algorithmId: String, stableDays: Int): Int {
    for (injectedRecords in 0..stableDays) {
        val prefix = records.filter { record ->
            record.dateOfSleep < START_DATE.plusDays((FREE_RUNNING_DAYS + injectedRecords).toLong())
        }
        val slope = forecastSlope(
            CircadianAlgorithmRegistry.analyze(
                prefix,
                extraDays = FORECAST_DAYS,
                algorithmId = algorithmId,
                overrides = RECENT_TREND_KALMAN_OVERRIDES,
            ),
        )
        if (abs(slope) <= RESPONSIVE_DRIFT) return injectedRecords
    }
    return stableDays + 1
}

private fun productionKalmanForecastSlope(records: List<SleepRecord>) =
    forecastSlope(productionKalmanAnalysis(records))

private fun productionKalmanAnalysis(records: List<SleepRecord>) =
    CircadianAlgorithmRegistry.analyze(
        records,
        extraDays = FORECAST_DAYS,
        algorithmId = CircadianAlgorithmRegistry.KALMAN_ID,
        overrides = RECENT_TREND_KALMAN_OVERRIDES,
    )

private fun terminalObservedMidpoint(analysis: CircadianAnalysis): Double {
    val terminal = analysis.days.last { !it.isForecast && !it.isGap }
    return normalizeRecentTrendHour((terminal.nightStartHour + terminal.nightEndHour) / 2.0)
}

private fun recentEntrainmentFixture() = generateSyntheticRecords(
    SyntheticOptions(
        days = FREE_RUNNING_DAYS + STABLE_DAYS,
        noise = 0.15,
        seed = 11,
        quality = 1.0,
        startDate = START_DATE,
        startMidpoint = 3.0,
        tauSegments = listOf(
            TauSegment(untilDay = FREE_RUNNING_DAYS, tau = 25.0),
            TauSegment(untilDay = FREE_RUNNING_DAYS + STABLE_DAYS, tau = 24.0),
        ),
    ),
)

private fun exactClockEntrainmentFixture(): List<SleepRecord> {
    val generated = generateSyntheticRecords(
        SyntheticOptions(
            days = FREE_RUNNING_DAYS + EXACT_CLOCK_DAYS,
            tau = 25.0,
            noise = 0.25,
            quality = 1.0,
            seed = 20260715,
            startDate = START_DATE,
        ),
    )
    return generated.map { record ->
        val day = java.time.temporal.ChronoUnit.DAYS.between(START_DATE, record.dateOfSleep).toInt()
        if (day < FREE_RUNNING_DAYS) return@map record
        val start = record.dateOfSleep.atTime(23, 27).toInstant(ZoneOffset.UTC)
        val end = start.plus(Duration.ofHours(7))
        record.copy(
            startTime = start,
            endTime = end,
            durationMs = Duration.between(start, end).toMillis(),
            durationHours = 7.0,
            minutesAsleep = 378,
            minutesAwake = 42,
            sleepScore = 1.0,
        )
    }
}

private fun forecastSlope(analysis: CircadianAnalysis): Double {
    val phases = analysis.days
        .filter { it.isForecast && !it.isGap }
        .sortedBy(CircadianDay::date)
        .map { day -> (day.nightStartHour + day.nightEndHour) / 2.0 }
    require(phases.size >= 2)
    val unwrapped = mutableListOf(normalizeRecentTrendHour(phases.first()))
    phases.drop(1).forEach { phase ->
        val normalized = normalizeRecentTrendHour(phase)
        unwrapped += normalized + round((unwrapped.last() - normalized) / 24.0) * 24.0
    }
    val meanX = unwrapped.lastIndex / 2.0
    val meanY = unwrapped.average()
    return unwrapped.indices.sumOf { index -> (index - meanX) * (unwrapped[index] - meanY) } /
        unwrapped.indices.sumOf { index -> (index - meanX) * (index - meanX) }
}

private fun normalizeRecentTrendHour(hour: Double) = ((hour % 24.0) + 24.0) % 24.0
private fun circularRecentTrendDifference(first: Double, second: Double): Double {
    var difference = normalizeRecentTrendHour(first) - normalizeRecentTrendHour(second)
    if (difference > 12.0) difference -= 24.0
    if (difference <= -12.0) difference += 24.0
    return difference
}
private fun Double.format() = String.format(java.util.Locale.ROOT, "%.2f", this)

private val START_DATE: LocalDate = LocalDate.parse("2026-04-02")
private const val FREE_RUNNING_DAYS = 90
private const val STABLE_DAYS = 11
private const val FORECAST_DAYS = 14
private const val RESPONSIVE_DRIFT = 0.75
private const val STABLE_N24_TOLERANCE = 0.25
private const val ENDPOINT_OUTLIER_TOLERANCE = 0.15
private const val ENDPOINT_PHASE_OUTLIER_TOLERANCE = 1.0
private const val NOISY_TRANSITION_SEEDS = 12
private const val NOISY_STABLE_DAYS = 18
private const val MIN_MEAN_RESPONSE_LEAD_DAYS = 0.5
private const val EXACT_CLOCK_DAYS = 14
private const val MAX_TERMINAL_PHASE_ERROR_HOURS = 1.0
private val RECENT_TREND_KALMAN_OVERRIDES = mapOf(
    "drift_prior" to 1.0,
    "phase_variance" to 0.42,
    "drift_variance" to 0.0001,
    "measurement_variance" to 8.0,
)
