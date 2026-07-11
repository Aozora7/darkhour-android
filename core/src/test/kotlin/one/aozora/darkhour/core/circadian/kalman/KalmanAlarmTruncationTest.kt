package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.circadian.groundtruth.GroundTruthFixtures
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.round
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KalmanAlarmTruncationTest {
    @Test
    fun temporaryAlarmTruncationDoesNotReplaceTheFreeRunningTrend() {
        val records = alarmTruncationFixture()
        val analysis = analyzeCircadianKalman(records, config = productionKalmanConfig())
        val alarmEndDate = START_DATE.plusDays(ALARM_END_DAY.toLong())
        val alarmAndRecovery = analysis.days.filter {
            !it.isGap && !it.isForecast && it.date in ALARM_START_DATE..alarmEndDate.plusDays(14)
        }
        val recovered = analysis.days.filter {
            !it.isGap && !it.isForecast && it.date >= alarmEndDate.plusDays(7)
        }.take(14)

        assertTrue("fixture must resemble a lower-tau plateau", alarmMidpointSlope(records) < 0.65)
        assertTrue("alarm truncation created a false change point: ${analysis.changePoints}", analysis.changePoints.isEmpty())
        assertTrue("alarm period must contain shortened main sleeps", records.any {
            it.dateOfSleep in ALARM_START_DATE..alarmEndDate && it.durationHours < 4.0
        })
        assertTrue(
            "Kalman followed the alarm plateau: ${alarmAndRecovery.map { it.localDrift }}",
            alarmAndRecovery.map { it.localDrift }.average() > 0.65,
        )
        assertTrue(
            "Kalman did not recover the free-running trend: ${recovered.map { it.localDrift }}",
            recovered.map { it.localDrift }.average() > 0.75,
        )
    }

    @Test
    fun privateAlarmEpisodeDoesNotCreateABoundaryWhenFixtureIsAvailable() {
        assumeTrue("Private ground-truth fixtures are not available", GroundTruthFixtures.isAvailable)
        val definition = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.KALMAN_ID)
        val analysis = definition.analyze(
            records = GroundTruthFixtures.load("Aozora_2026-02-13").records,
            extraDays = 0,
            values = CircadianAlgorithmRegistry.resolvedValues(definition.id, emptyMap()),
        ) as KalmanAnalysis

        val episode = LocalDate.parse("2025-03-24")..LocalDate.parse("2025-04-08")
        assertTrue(
            "private alarm episode created a change point: ${analysis.changePoints}",
            analysis.changePoints.none { it.date in episode },
        )
    }
}

private fun alarmTruncationFixture(): List<SleepRecord> {
    val records = generateSyntheticRecords(
        SyntheticOptions(
            tau = 25.0,
            days = TOTAL_DAYS,
            noise = 0.15,
            seed = 20250401,
            startDate = START_DATE,
            startMidpoint = 2.0 - ALARM_START_DAY,
        ),
    )
    return records.map { record ->
        val day = Duration.between(START_DATE.atStartOfDay(), record.dateOfSleep.atStartOfDay()).toDays().toInt()
        if (day !in ALARM_START_DAY..ALARM_END_DAY) return@map record
        val alarmEnd = record.dateOfSleep.atStartOfDay().toInstant(ZoneOffset.UTC).plus(Duration.ofHours(6))
        if (record.endTime <= alarmEnd) return@map record
        val duration = Duration.between(record.startTime, alarmEnd)
        record.copy(
            endTime = alarmEnd,
            durationMs = duration.toMillis(),
            durationHours = duration.toMillis() / 3_600_000.0,
            minutesAsleep = (duration.toMinutes() * 0.9).toInt(),
            minutesAwake = (duration.toMinutes() * 0.1).toInt(),
        )
    }
}

private fun alarmMidpointSlope(records: List<SleepRecord>): Double {
    val midpoints = records
        .filter { it.dateOfSleep in ALARM_START_DATE..START_DATE.plusDays(ALARM_END_DAY.toLong()) }
        .sortedBy(SleepRecord::dateOfSleep)
        .map { record ->
            val midnight = record.dateOfSleep.atStartOfDay().toInstant(ZoneOffset.UTC)
            Duration.between(midnight, record.startTime).toMillis() / 3_600_000.0 + record.durationHours / 2.0
        }
    val unwrapped = mutableListOf(midpoints.first())
    midpoints.drop(1).forEach { midpoint ->
        unwrapped += midpoint + round((unwrapped.last() - midpoint) / 24.0) * 24.0
    }
    val meanX = unwrapped.lastIndex / 2.0
    val meanY = unwrapped.average()
    return unwrapped.indices.sumOf { (it - meanX) * (unwrapped[it] - meanY) } /
        unwrapped.indices.sumOf { (it - meanX) * (it - meanX) }
}

private const val TOTAL_DAYS = 120
private const val ALARM_START_DAY = 70
private const val ALARM_END_DAY = 78
private val START_DATE = LocalDate.parse("2025-01-13")
private val ALARM_START_DATE = START_DATE.plusDays(ALARM_START_DAY.toLong())

private fun productionKalmanConfig() = KalmanConfig(
    driftPrior = 1.0,
    processPhaseVariance = 0.42,
    processDriftVariance = 0.0001,
    measurementVarianceAtUnitWeight = 8.0,
)
