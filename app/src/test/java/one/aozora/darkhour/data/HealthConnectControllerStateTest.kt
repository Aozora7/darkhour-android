package one.aozora.darkhour.data

import one.aozora.darkhour.core.model.SleepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HealthConnectControllerStateTest {
    @Test
    fun providerUnavailableClearsReadDataWithoutOverwritingFileOperationState() {
        val current = populatedState().copy(
            fileOperation = HealthConnectFileOperation.EXPORTING,
            fileOperationMessage = "Exporting",
        )

        val updated = current.withProviderUnavailable(HealthConnectAccess.UPDATE_REQUIRED)

        assertEquals(HealthConnectAccess.UPDATE_REQUIRED, updated.access)
        assertEquals(emptyList<SleepRecord>(), updated.records)
        assertEquals(emptyMap<Long, SleepRecordDisplayMetadata>(), updated.recordMetadata)
        assertEquals(emptyList<SleepRecord>(), updated.analysisRecords)
        assertNull(updated.totalHistoryDays)
        assertEquals(HealthDataRange.MINIMUM_CUSTOM_DAYS, updated.availableHistoryDays)
        assertEquals(HistoryPermissionState.UNAVAILABLE, updated.historyPermissionState)
        assertFalse(updated.isRefreshing)
        assertEquals(HealthConnectFileOperation.EXPORTING, updated.fileOperation)
        assertEquals("Exporting", updated.fileOperationMessage)
    }

    @Test
    fun readPermissionTransitionKeepsKnownHistoryOnlyWhenHistoryPermissionRemainsGranted() {
        val current = populatedState()

        assertEquals(
            120,
            current.withReadPermissionRequired(HistoryPermissionState.GRANTED).totalHistoryDays,
        )
        assertNull(
            current.withReadPermissionRequired(HistoryPermissionState.AVAILABLE_NOT_GRANTED)
                .totalHistoryDays,
        )
    }

    @Test
    fun refreshCompletionPreservesKnownHistoryWhenImportDoesNotDiscoverOldestRecord() {
        val updated = populatedState().withRefreshCompleted(
            records = listOf(sleepRecord(2)),
            recordMetadata = mapOf(2L to SleepRecordDisplayMetadata(sourceName = "Source")),
            analysisRecords = listOf(sleepRecord(3)),
            importedTotalHistoryDays = null,
            importedAvailableHistoryDays = 30,
            historyPermissionState = HistoryPermissionState.GRANTED,
            fileImportedRecordCount = 1,
        )

        assertEquals(120, updated.totalHistoryDays)
        assertEquals(1, updated.importedRecordCount)
        assertEquals(1, updated.expectedRecordCount)
        assertEquals(1, updated.fileImportedRecordCount)
        assertFalse(updated.isRefreshing)
        assertEquals(HealthImportPhase.IDLE, updated.importPhase)
    }

    @Test
    fun partialProgressWithoutRecordsPreservesLastPublishedData() {
        val current = populatedState()

        val updated = current.withImportProgress(
            records = null,
            recordMetadata = null,
            analysisRecords = null,
            importedRecordCount = 5,
            expectedRecordCount = 10,
            isImportPartial = true,
            phase = HealthImportPhase.HISTORY,
        )

        assertEquals(current.records, updated.records)
        assertEquals(current.recordMetadata, updated.recordMetadata)
        assertEquals(current.analysisRecords, updated.analysisRecords)
        assertEquals(5, updated.importedRecordCount)
        assertEquals(10, updated.expectedRecordCount)
        assertTrue(updated.isImportPartial)
        assertEquals(HealthImportPhase.HISTORY, updated.importPhase)
    }

    @Test
    fun startingFileOperationClearsOnlyPreviousOperationResults() {
        val current = populatedState().copy(
            fileImportResult = importResult,
            exportResult = SleepExportResult(2, 1),
            fileOperationMessage = "Previous result",
            fileOperationErrorMessage = "Previous error",
        )

        val updated = current.withFileOperationStarted(HealthConnectFileOperation.IMPORTING)

        assertEquals(HealthConnectFileOperation.IMPORTING, updated.fileOperation)
        assertNull(updated.fileImportResult)
        assertNull(updated.exportResult)
        assertNull(updated.fileOperationMessage)
        assertNull(updated.fileOperationErrorMessage)
        assertEquals(current.records, updated.records)
        assertEquals(current.statsAllRecords, updated.statsAllRecords)
    }

    @Test
    fun statsTransitionsDoNotOverwriteSelectedRangeData() {
        val current = populatedState()
        val statsRecords = listOf(sleepRecord(4))

        val updated = current
            .withStatsRefreshStarted(HistoryPermissionState.GRANTED)
            .withStatsRefreshCompleted(
                statsRecords,
                importedTotalHistoryDays = 180,
                importedAvailableHistoryDays = 180,
                historyPermissionState = HistoryPermissionState.GRANTED,
            )

        assertEquals(current.records, updated.records)
        assertEquals(statsRecords, updated.statsAllRecords)
        assertEquals(180, updated.totalHistoryDays)
        assertEquals(HistoryPermissionState.GRANTED, updated.historyPermissionState)
        assertFalse(updated.isStatsAllDataRefreshing)
    }

    @Test
    fun limitedHistoryMaximumOnlyGrowsWhenShorterRangesAreLoaded() {
        val current = populatedState().copy(
            totalHistoryDays = null,
            availableHistoryDays = 120,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
        )

        val shorter = current.withRefreshCompleted(
            records = listOf(sleepRecord(2)),
            recordMetadata = emptyMap(),
            analysisRecords = listOf(sleepRecord(2)),
            importedTotalHistoryDays = null,
            importedAvailableHistoryDays = 45,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
            fileImportedRecordCount = 0,
        )
        val longer = shorter.withRefreshCompleted(
            records = listOf(sleepRecord(3)),
            recordMetadata = emptyMap(),
            analysisRecords = listOf(sleepRecord(3)),
            importedTotalHistoryDays = null,
            importedAvailableHistoryDays = 200,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
            fileImportedRecordCount = 0,
        )

        assertEquals(120, shorter.availableHistoryDays)
        assertEquals(200, longer.availableHistoryDays)
        assertNull(longer.totalHistoryDays)
    }

    private fun populatedState(): HealthConnectUiState {
        val record = sleepRecord(1)
        return HealthConnectUiState(
            records = listOf(record),
            recordMetadata = mapOf(record.logId to SleepRecordDisplayMetadata(sourceName = "App")),
            analysisRecords = listOf(record),
            statsAllRecords = listOf(record),
            access = HealthConnectAccess.CONNECTED,
            totalHistoryDays = 120,
            availableHistoryDays = 120,
            historyPermissionState = HistoryPermissionState.GRANTED,
            isRefreshing = true,
            isStatsAllDataRefreshing = true,
            importedRecordCount = 1,
            fileImportedRecordCount = 1,
            expectedRecordCount = 2,
            isImportPartial = true,
            importPhase = HealthImportPhase.HISTORY,
            errorMessage = "Old error",
            statsAllDataErrorMessage = "Old stats error",
        )
    }

    private fun sleepRecord(id: Long): SleepRecord {
        val start = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id * 86_400)
        val end = start.plusSeconds(8 * 3_600)
        return SleepRecord(
            logId = id,
            dateOfSleep = LocalDate.ofInstant(start, java.time.ZoneOffset.UTC),
            startTime = start,
            endTime = end,
            durationMs = 8 * 3_600_000L,
            durationHours = 8.0,
            efficiency = 90,
            minutesAsleep = 432,
            minutesAwake = 48,
            isMainSleep = true,
        )
    }

    private companion object {
        val importResult = SleepFileImportResult(
            selectedFileCount = 1,
            recognizedFileCount = 1,
            processedRecordCount = 1,
            committedRecordCount = 1,
            skippedRecordCount = 0,
            fallbackZoneRecordCount = 0,
            issues = emptyList(),
        )
    }
}
