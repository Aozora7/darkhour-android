package one.aozora.darkhour.data

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import one.aozora.darkhour.core.model.SleepStageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectSleepImportTest {
    @Test
    fun customRangeFiltersRawSessionsBeforeConversion() {
        val now = Instant.parse("2026-06-20T00:00:00Z")
        val oldStart = now.minusSeconds(90L * 24 * 60 * 60)
        val includedStart = now.minusSeconds(45L * 24 * 60 * 60)
        val oldRecord = sleepRecord(oldStart)
        val includedRecord = sleepRecord(includedStart)

        val imported = importSleepRecords(
            rawRecords = listOf(oldRecord, includedRecord),
            range = HealthDataRange.custom(60),
            now = now,
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals(1, imported.records.size)
        assertEquals(includedStart, imported.records.single().record.startTime)
        assertEquals(91, imported.totalHistoryDays)
    }

    @Test
    fun customRangeClampsToMinimumDayCount() {
        assertEquals(HealthDataRange.Custom(30), HealthDataRange.custom(10))
    }

    @Test
    fun defaultRangePlansOnlyRecentThirtyDays() {
        val now = Instant.parse("2026-06-20T00:00:00Z")

        val ranges = healthConnectReadRanges(HealthDataRange.DEFAULT_PERIOD, now)

        assertEquals(listOf(HealthConnectReadRange(now.minusSeconds(30L * 24 * 60 * 60), now)), ranges)
    }

    @Test
    fun allHistoryPlansRecentFirstThenOlderChunks() {
        val now = Instant.parse("2026-06-20T00:00:00Z")
        val recentStart = now.minusSeconds(30L * 24 * 60 * 60)

        val ranges = healthConnectReadRanges(HealthDataRange.ENTIRE_HISTORY, now)

        assertEquals(HealthConnectReadRange(recentStart, now), ranges.first())
        assertEquals(recentStart, ranges[1].end)
        assertEquals(Instant.EPOCH, ranges.last().start)
    }

    @Test
    fun allHistoryPlanCanBeBoundedByDiscoveredOldestRecord() {
        val now = Instant.parse("2026-06-20T00:00:00Z")
        val oldest = Instant.parse("2022-06-20T00:00:00Z")

        val ranges = healthConnectReadRanges(
            range = HealthDataRange.ENTIRE_HISTORY,
            now = now,
            oldestAvailableStart = oldest,
        )

        assertEquals(oldest, ranges.last().start)
    }

    @Test
    fun customHistoryRangeStopsAtRequestedDayCount() {
        val now = Instant.parse("2026-06-20T00:00:00Z")
        val oldestStart = now.minusSeconds(90L * 24 * 60 * 60)
        val recentStart = now.minusSeconds(30L * 24 * 60 * 60)

        val ranges = healthConnectReadRanges(HealthDataRange.custom(90), now)

        assertEquals(
            listOf(
                HealthConnectReadRange(recentStart, now),
                HealthConnectReadRange(oldestStart, recentStart),
            ),
            ranges,
        )
    }

    @Test
    fun accumulatorDeduplicatesByStableGeneratedIdentity() {
        val start = Instant.parse("2026-06-10T21:00:00Z")
        val accumulator = ImportedSleepAccumulator(ZoneId.of("UTC"))

        accumulator.add(
            listOf(
                sleepRecord(start),
                sleepRecord(start),
                sleepRecord(start.plusSeconds(24 * 60 * 60)),
            ),
        )

        assertEquals(2, accumulator.size)
        assertEquals(start, accumulator.sortedRecords().first().record.startTime)
    }

    @Test
    fun mapsStagesTotalsOffsetsAndSourceIdentity() {
        val start = Instant.parse("2026-06-10T21:00:00Z")
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.ofHours(2),
            endTime = start.plusSeconds(8 * 60 * 60),
            endZoneOffset = ZoneOffset.ofHours(2),
            metadata = Metadata.manualEntry(),
            stages = listOf(
                stage(start, 60, SleepSessionRecord.STAGE_TYPE_AWAKE),
                stage(start.plusSeconds(60 * 60), 120, SleepSessionRecord.STAGE_TYPE_DEEP),
                stage(start.plusSeconds(3 * 60 * 60), 120, SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage(start.plusSeconds(5 * 60 * 60), 180, SleepSessionRecord.STAGE_TYPE_REM),
            ),
        )

        val imported = record.toImportedSleepRecord(ZoneId.of("Europe/Riga"))
        val sleep = imported.record

        assertEquals(420, sleep.minutesAsleep)
        assertEquals(60, sleep.minutesAwake)
        assertEquals(120, sleep.stages?.deep)
        assertEquals(120, sleep.stages?.light)
        assertEquals(180, sleep.stages?.rem)
        assertEquals(60, sleep.stages?.wake)
        assertTrue(sleep.isMainSleep)
        assertEquals(SleepStageLevel.WAKE, sleep.stageData.first().level)
        assertEquals(LocalDate.parse("2026-06-10"), sleep.dateOfSleep)
        assertEquals(ZoneOffset.ofHours(2), sleep.startZoneOffset)
        assertTrue(sleep.sleepScore != null)
    }

    @Test
    fun crossMidnightSessionUsesLocalStartDateForAlgorithmParity() {
        val start = Instant.parse("2026-06-10T21:00:00Z")
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.ofHours(2),
            endTime = start.plusSeconds(8 * 60 * 60),
            endZoneOffset = ZoneOffset.ofHours(2),
            metadata = Metadata.manualEntry(),
            stages = emptyList(),
        )

        val sleep = record.toImportedSleepRecord(ZoneId.of("Europe/Riga")).record

        assertEquals(LocalDate.parse("2026-06-10"), sleep.dateOfSleep)
        assertNull(sleep.stages)
        assertTrue(sleep.stageData.isEmpty())
    }

    @Test
    fun shortSessionIsTreatedAsNapAndUnknownSleepAsLight() {
        val start = Instant.parse("2026-06-10T12:00:00Z")
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = start.plusSeconds(90 * 60),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            stages = listOf(
                stage(start, 90, SleepSessionRecord.STAGE_TYPE_SLEEPING),
            ),
        )

        val sleep = record.toImportedSleepRecord(ZoneId.of("UTC")).record

        assertFalse(sleep.isMainSleep)
        assertEquals(90, sleep.minutesAsleep)
        assertEquals(90, sleep.stages?.light)
        assertEquals(SleepStageLevel.LIGHT, sleep.stageData.single().level)
    }

    private fun stage(
        start: Instant,
        minutes: Long,
        type: Int,
    ) = SleepSessionRecord.Stage(
        startTime = start,
        endTime = start.plusSeconds(minutes * 60),
        stage = type,
    )

    private fun sleepRecord(start: Instant) = SleepSessionRecord(
        startTime = start,
        startZoneOffset = ZoneOffset.UTC,
        endTime = start.plusSeconds(8 * 60 * 60),
        endZoneOffset = ZoneOffset.UTC,
        metadata = Metadata.manualEntry(),
        stages = emptyList(),
    )
}
