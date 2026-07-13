package one.aozora.darkhour.data

import androidx.health.connect.client.records.metadata.Metadata
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.ui.stats.calculateStatsMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HealthConnectSleepDeduplicationTest {
    @Test
    fun exactIdentityUsesCanonicalRecordRegardlessOfInputOrder() {
        val plain = imported(startMinutes = 0, durationMinutes = 480, source = "app.a", id = "same")
        val staged = imported(
            startMinutes = 0,
            durationMinutes = 480,
            source = "app.a",
            id = "same",
            stages = listOf(stage(0, 480, SleepStageLevel.LIGHT)),
        )

        val forward = resolveImportedSleepRecords(listOf(plain, staged))
        val reverse = resolveImportedSleepRecords(listOf(staged, plain))

        assertEquals(1, forward.records.size)
        assertEquals(480 * 60L, forward.records.single().usableStageSeconds)
        assertEquals(forward, reverse)
    }

    @Test
    fun exactCrossSourceCopiesCollapseForDisplayAndAnalysis() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 480, "app.a", "a"),
                imported(0, 480, "app.b", "b"),
            ),
        )

        assertEquals(1, result.records.size)
        assertEquals(1, result.analysisRecords.size)
        assertEquals(480, result.analysisRecords.single().record.minutesAsleep)
    }

    @Test
    fun eightyPercentOverlapCollapsesButJustUnderRemainsVisible() {
        val atThreshold = resolveImportedSleepRecords(
            listOf(
                imported(0, 100, "app.a", "a"),
                imported(20, 100, "app.b", "b"),
            ),
        )
        val belowThreshold = resolveImportedSleepRecords(
            listOf(
                imported(0, 100, "app.a", "a"),
                imported(21, 100, "app.b", "b"),
            ),
        )

        assertEquals(1, atThreshold.records.size)
        assertEquals(2, belowThreshold.records.size)
        assertEquals(1, belowThreshold.analysisRecords.size)
        assertEquals(121, belowThreshold.analysisRecords.single().record.minutesAsleep)
    }

    @Test
    fun containedRecordUsesShorterSessionOverlapRatio() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 480, "app.a", "long"),
                imported(120, 120, "app.b", "contained"),
            ),
        )

        assertEquals(1, result.records.size)
        assertEquals(480, result.analysisRecords.single().record.minutesAsleep)
    }

    @Test
    fun partialOverlapStaysVisibleButSharedTimeCountsOnce() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, "app.a", "a"),
                imported(120, 180, "app.b", "b"),
            ),
        )

        assertEquals(2, result.records.size)
        assertEquals(1, result.analysisRecords.size)
        val episode = result.analysisRecords.single().record
        assertEquals(300, episode.durationMs / 60_000L)
        assertEquals(300, episode.minutesAsleep)

        val metrics = calculateStatsMetrics(listOf(episode), dailyDriftHours = 0.0)
        assertEquals(5.0, metrics.sleepHoursPerDay ?: error("missing metric"), 0.0001)
        assertEquals(5.0, metrics.timeInBedHoursPerDay ?: error("missing metric"), 0.0001)
        assertEquals(1, buildPeriodogramAnchors(result.analysisRecords.map { it.record }).size)
    }

    @Test
    fun sameUnknownAdjacentAndNonOverlappingSourcesStayIndependent() {
        val sameSource = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, "app.a", "a1"),
                imported(120, 180, "app.a", "a2"),
            ),
        )
        val unknownSource = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, null, "u1"),
                imported(120, 180, "app.b", "b"),
            ),
        )
        val adjacent = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, "app.a", "a"),
                imported(180, 180, "app.b", "b"),
                imported(500, 60, "app.c", "c"),
            ),
        )

        assertEquals(2, sameSource.analysisRecords.size)
        assertEquals(2, unknownSource.analysisRecords.size)
        assertEquals(3, adjacent.analysisRecords.size)
    }

    @Test
    fun transitiveOverlapProducesOneStableAnalysisEpisode() {
        val records = listOf(
            imported(0, 120, "app.a", "a"),
            imported(90, 120, "app.b", "b"),
            imported(180, 120, "app.c", "c"),
        )

        val forward = resolveImportedSleepRecords(records)
        val reverse = resolveImportedSleepRecords(records.reversed())

        assertEquals(3, forward.records.size)
        assertEquals(1, forward.analysisRecords.size)
        assertEquals(300, forward.analysisRecords.single().record.minutesAsleep)
        assertEquals(
            forward.analysisRecords.single().record.logId,
            reverse.analysisRecords.single().record.logId,
        )
    }

    @Test
    fun canonicalStagesWinConflictsAndLowerRankedStagesFillGaps() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(
                    0,
                    120,
                    "app.a",
                    "a",
                    stages = listOf(stage(0, 120, SleepStageLevel.DEEP)),
                ),
                imported(
                    60,
                    120,
                    "app.b",
                    "b",
                    stages = listOf(stage(60, 120, SleepStageLevel.WAKE)),
                ),
            ),
        )

        val episode = result.analysisRecords.single().record
        assertEquals(120, episode.stages?.deep)
        assertEquals(60, episode.stages?.wake)
        assertEquals(120, episode.minutesAsleep)
        assertEquals(60, episode.minutesAwake)
        assertEquals(2, episode.stageData.size)
        assertEquals(SleepStageLevel.DEEP, episode.stageData[0].level)
        assertEquals(SleepStageLevel.WAKE, episode.stageData[1].level)
    }

    @Test
    fun unionMainSleepClassificationIsPromotedToVisibleFragments() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, "app.a", "a"),
                imported(120, 180, "app.b", "b"),
            ),
        )

        assertTrue(result.analysisRecords.single().record.isMainSleep)
        assertTrue(result.records.all { it.record.isMainSleep })
    }

    @Test
    fun consolidatedRecordWithoutStagesKeepsStageDataUnavailable() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 180, "app.a", "a"),
                imported(120, 180, "app.b", "b"),
            ),
        )

        val episode = result.analysisRecords.single().record
        assertNull(episode.stages)
        assertTrue(episode.stageData.isEmpty())
        assertFalse(episode.sleepScore == null)
    }

    @Test
    fun stageRichSourceWinsMostlyOverlappingDisplaySelection() {
        val result = resolveImportedSleepRecords(
            listOf(
                imported(0, 480, "app.z", "plain"),
                imported(
                    10,
                    470,
                    "app.a",
                    "staged",
                    stages = listOf(stage(10, 470, SleepStageLevel.LIGHT)),
                    recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY,
                ),
            ),
        )

        assertEquals("staged", result.records.single().sourceRecordId)
    }

    private fun imported(
        startMinutes: Long,
        durationMinutes: Long,
        source: String?,
        id: String,
        stages: List<SleepStageInterval> = emptyList(),
        recordingMethod: Int = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
    ): ImportedSleepRecord {
        val start = BASE.plusSeconds(startMinutes * 60)
        val end = start.plusSeconds(durationMinutes * 60)
        val stageSeconds = stages.sumOf { it.seconds.toLong() }
        val stageMinutes = stages
            .groupingBy(SleepStageInterval::level)
            .fold(0) { total, stage -> total + stage.seconds / 60 }
        val wakeMinutes = stageMinutes[SleepStageLevel.WAKE] ?: 0
        val minutes = durationMinutes.toInt()
        return ImportedSleepRecord(
            record = SleepRecord(
                logId = id.hashCode().toLong() and Long.MAX_VALUE,
                dateOfSleep = LocalDate.parse("2026-01-01"),
                startTime = start,
                endTime = end,
                durationMs = durationMinutes * 60_000,
                durationHours = durationMinutes / 60.0,
                efficiency = if (minutes > 0) (minutes - wakeMinutes) * 100 / minutes else 0,
                minutesAsleep = minutes - wakeMinutes,
                minutesAwake = wakeMinutes,
                isMainSleep = durationMinutes >= 240,
                sleepScore = 0.8,
                stages = if (stages.isEmpty()) {
                    null
                } else {
                    SleepStages(
                        deep = stageMinutes[SleepStageLevel.DEEP] ?: 0,
                        light = stageMinutes[SleepStageLevel.LIGHT] ?: 0,
                        rem = stageMinutes[SleepStageLevel.REM] ?: 0,
                        wake = wakeMinutes,
                    )
                },
                stageData = stages,
                startZoneOffset = ZoneOffset.UTC,
                endZoneOffset = ZoneOffset.UTC,
            ),
            sourceRecordId = id,
            sourcePackageName = source,
            sourceRecordingMethod = recordingMethod,
            sourceLastModifiedTime = BASE,
            specificStageSeconds = stageSeconds,
            usableStageSeconds = stageSeconds,
        )
    }

    private fun stage(
        startMinutes: Long,
        durationMinutes: Int,
        level: SleepStageLevel,
    ) = SleepStageInterval(
        startTime = BASE.plusSeconds(startMinutes * 60),
        level = level,
        seconds = durationMinutes * 60,
    )

    private companion object {
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
