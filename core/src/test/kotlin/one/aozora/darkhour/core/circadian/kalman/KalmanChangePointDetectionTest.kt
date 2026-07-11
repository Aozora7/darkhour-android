package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.TauSegment
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class KalmanChangePointDetectionTest {
    @Test
    fun inverseEntrainmentToFreeRunningStepIsDetected() {
        val transitionDay = 80
        val records = generateSyntheticRecords(
            baseOptions(
                days = 105,
                tauSegments = listOf(
                    TauSegment(transitionDay, 24.0),
                    TauSegment(105, 25.0),
                ),
            ),
        )

        val change = analyzeCircadianKalman(records, config = productionConfig()).changePoints.single()
        val expected = START_DATE.plusDays(transitionDay.toLong())
        assertTrue("inverse boundary ${change.date} was not near $expected", dayDistance(change.date, expected) <= 2)
        assertTrue("inverse slope was not recognized: $change", change.newDrift > 0.70)
    }

    @Test
    fun stableNoisySparseAndGappySeriesDoNotCreateBoundaries() {
        val stable = generateSyntheticRecords(baseOptions(days = 140, noise = 0.25))
        val noisy = generateSyntheticRecords(baseOptions(days = 140, noise = 0.75, seed = 19))
        val sparse = stable.filterIndexed { index, _ -> index % 3 != 1 }
        val gappy = stable.filterNot { it.dateOfSleep in START_DATE.plusDays(50)..START_DATE.plusDays(75) }

        mapOf("stable" to stable, "noisy" to noisy, "sparse" to sparse, "gappy" to gappy).forEach { (name, records) ->
            val changes = analyzeCircadianKalman(records, config = productionConfig()).changePoints
            assertTrue("$name series created false boundaries: $changes", changes.isEmpty())
        }
    }

    @Test
    fun gradualDriftDoesNotBecomeAnAbruptBoundary() {
        val records = generateSyntheticRecords(baseOptions(days = 140, tau = 24.4, noise = 0.20, seed = 31))
            .mapIndexed { day, record -> shift(record, 0.5 * (0.4 / 139.0) * day * day) }

        val changes = analyzeCircadianKalman(records, config = productionConfig()).changePoints
        assertTrue("gradual drift created false boundaries: $changes", changes.isEmpty())
    }
}

private fun baseOptions(
    days: Int,
    tau: Double = 25.0,
    noise: Double = 0.20,
    seed: Int = 11,
    tauSegments: List<TauSegment> = emptyList(),
) = SyntheticOptions(
    days = days,
    tau = tau,
    noise = noise,
    seed = seed,
    startDate = START_DATE,
    quality = 1.0,
    tauSegments = tauSegments,
)

private fun productionConfig() = KalmanConfig(
    driftPrior = 1.0,
    processPhaseVariance = 0.42,
    processDriftVariance = 0.0001,
    measurementVarianceAtUnitWeight = 8.0,
)

private fun shift(record: SleepRecord, hours: Double): SleepRecord {
    val millis = (hours * 3_600_000.0).toLong()
    return record.copy(startTime = record.startTime.plusMillis(millis), endTime = record.endTime.plusMillis(millis))
}

private fun dayDistance(a: LocalDate, b: LocalDate): Long =
    abs(Duration.between(a.atStartOfDay(), b.atStartOfDay()).toDays())

private val START_DATE = LocalDate.parse("2025-01-01")
