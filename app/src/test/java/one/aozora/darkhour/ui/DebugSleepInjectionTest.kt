package one.aozora.darkhour.ui

import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSleepInjectionTest {
    @Test
    fun defaultsFollowTheLastSleepRecord() {
        val lastRecord = record(
            date = LocalDate.parse("2026-07-11"),
            start = Instant.parse("2026-07-11T21:15:00Z"),
        )

        val form = defaultDebugSleepInjectionForm(listOf(lastRecord), ZoneOffset.UTC)

        assertEquals("2026-07-12", form.date)
        assertEquals("21:15", form.timeFrom)
        assertEquals("04:15", form.timeTo)
        assertEquals("0", form.driftMinutesPerDay)
        assertEquals("1", form.numberOfDays)
    }

    @Test
    fun repeatedSingleAddsEqualOneBatchAdd() {
        val batchForm = DebugSleepInjectionForm(
            date = "2026-07-12",
            timeFrom = "23:30",
            timeTo = "06:30",
            driftMinutesPerDay = "15",
            numberOfDays = "3",
        )
        val batch = generateDebugSleepRecords(batchForm, 0, ZoneOffset.UTC).getOrThrow()

        var singleForm = batchForm.copy(numberOfDays = "1")
        val singles = mutableListOf<SleepRecord>()
        repeat(3) {
            val result = generateDebugSleepRecords(singleForm, singles.size, ZoneOffset.UTC).getOrThrow()
            singles += result.records
            singleForm = result.nextForm
        }

        assertEquals(batch.records, singles)
        assertEquals(batch.nextForm.copy(numberOfDays = "1"), singleForm)
    }

    @Test
    fun driftAndMidnightCrossingPreserveDuration() {
        val result = generateDebugSleepRecords(
            DebugSleepInjectionForm("2026-07-12", "23:30", "06:30", "45", "2"),
            existingInjectedCount = 0,
            zoneId = ZoneOffset.UTC,
        ).getOrThrow()

        assertTrue(result.records.all { it.durationHours == 7.0 })
        assertEquals(Instant.parse("2026-07-12T23:30:00Z"), result.records[0].startTime)
        assertEquals(Instant.parse("2026-07-14T00:15:00Z"), result.records[1].startTime)
        assertEquals("2026-07-15", result.nextForm.date)
        assertEquals("01:00", result.nextForm.timeFrom)
        assertEquals("08:00", result.nextForm.timeTo)
    }

    @Test
    fun invalidValuesReturnAnErrorWithoutRecords() {
        val result = generateDebugSleepRecords(
            DebugSleepInjectionForm("not-a-date", "23:00", "06:00", "0", "1"),
            existingInjectedCount = 0,
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(result.isFailure)
    }
}

private fun record(date: LocalDate, start: Instant): SleepRecord {
    val duration = Duration.ofHours(7)
    return SleepRecord(
        logId = 1,
        dateOfSleep = date,
        startTime = start,
        endTime = start.plus(duration),
        durationMs = duration.toMillis(),
        durationHours = 7.0,
        efficiency = 100,
        minutesAsleep = 420,
        minutesAwake = 0,
        isMainSleep = true,
        sleepScore = 0.95,
        startZoneOffset = ZoneOffset.UTC,
        endZoneOffset = ZoneOffset.UTC,
    )
}
