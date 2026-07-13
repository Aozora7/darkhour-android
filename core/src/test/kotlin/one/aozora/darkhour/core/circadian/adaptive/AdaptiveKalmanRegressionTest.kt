package one.aozora.darkhour.core.circadian.adaptive

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.TauSegment
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.model.SleepRecord
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveKalmanRegressionTest {
    @Test
    fun persistentSleepPlacementOffsetDoesNotHideEntrainment() {
        val transitionDay = 80
        val records = generateSyntheticRecords(
            options(
                days = 105,
                tauSegments = listOf(TauSegment(transitionDay, 25.0), TauSegment(105, 24.0)),
            ),
        ).mapIndexed { day, record -> if (day < transitionDay) record else shift(record, 4.0) }

        val changes = analyzeCircadianAdaptiveKalman(records).changePoints
        assertEquals("unexpected transitions: $changes", 1, changes.size)
        val change = changes.single()

        assertTrue("coherent transition was not committed: $change", change.committed)
        assertTrue("boundary was $change", dayDistance(change.date, START_DATE.plusDays(transitionDay.toLong())) <= 2)
        assertTrue("new drift was ${change.newDrift}", abs(change.newDrift) < 0.25)
        assertTrue("confirmation lag was ${change.confirmationLagDays}", change.confirmationLagDays <= 10)
    }

    @Test
    fun entrainmentReleaseIsDetected() {
        val transitionDay = 80
        val records = generateSyntheticRecords(
            options(
                days = 110,
                tauSegments = listOf(TauSegment(transitionDay, 24.0), TauSegment(110, 25.0)),
            ),
        )

        val changes = analyzeCircadianAdaptiveKalman(records).changePoints
        assertEquals("unexpected transitions: $changes", 1, changes.size)
        val change = changes.single()

        assertTrue("coherent transition was not committed: $change", change.committed)
        assertTrue("boundary was $change", dayDistance(change.date, START_DATE.plusDays(transitionDay.toLong())) <= 2)
        assertTrue("new drift was ${change.newDrift}", change.newDrift > 0.70)
        assertTrue("confirmation lag was ${change.confirmationLagDays}", change.confirmationLagDays <= 10)
    }

    @Test
    fun temporaryAlarmTruncationDoesNotCreateTransitionEvidence() {
        val analysis = analyzeCircadianAdaptiveKalman(alarmTruncationFixture())

        assertTrue("alarm truncation created ${analysis.changePoints}", analysis.changePoints.isEmpty())
        val recoveryStart = START_DATE.plusDays((ALARM_END_DAY + 7).toLong())
        val recoveredDrift = analysis.days.filter { !it.isGap && !it.isForecast && it.date >= recoveryStart }
            .take(14)
            .map { it.localDrift }
            .average()
        assertTrue("recovery drift was $recoveredDrift", recoveredDrift > 0.75)
    }

    @Test
    fun sparseNoisyAndLongGapSeriesStayInOneRegime() {
        val stable = generateSyntheticRecords(options(days = 150, noise = 0.25))
        val noisy = generateSyntheticRecords(options(days = 150, noise = 0.75, seed = 91))
        val sparse = stable.filterIndexed { index, _ -> index % 3 != 1 }
        val gappy = stable.filterNot { it.dateOfSleep in START_DATE.plusDays(55)..START_DATE.plusDays(80) }

        mapOf("stable" to stable, "noisy" to noisy, "sparse" to sparse, "gappy" to gappy).forEach { (name, records) ->
            val changes = analyzeCircadianAdaptiveKalman(records).changePoints
            assertTrue("$name created transitions: $changes", changes.isEmpty())
        }
    }
}

private fun options(
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

private fun alarmTruncationFixture(): List<SleepRecord> = generateSyntheticRecords(
    options(days = 120, noise = 0.15, seed = 20250401),
).map { record ->
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

private fun shift(record: SleepRecord, hours: Double): SleepRecord {
    val millis = (hours * 3_600_000.0).toLong()
    return record.copy(startTime = record.startTime.plusMillis(millis), endTime = record.endTime.plusMillis(millis))
}

private fun dayDistance(first: LocalDate, second: LocalDate): Long =
    abs(Duration.between(first.atStartOfDay(), second.atStartOfDay()).toDays())

private const val ALARM_START_DAY = 70
private const val ALARM_END_DAY = 78
private val START_DATE = LocalDate.parse("2025-01-01")
