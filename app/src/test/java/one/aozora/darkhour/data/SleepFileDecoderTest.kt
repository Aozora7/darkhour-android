package one.aozora.darkhour.data

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SleepFileDecoderTest {
    @Test
    fun registryDetectsContentWithoutUsingFileName() {
        val source = resourceSource("misleading.csv", "fitbit-synthetic.json")

        val decoder = SleepFileDecoderRegistry().decoderFor(source)

        assertEquals("Fitbit", decoder?.formatName)
    }

    @Test
    fun registryRejectsUnknownAndAmbiguousEmptyFiles() {
        assertNull(SleepFileDecoderRegistry().decoderFor(source("unknown.json", "{\"value\":[]}")))
        assertNull(SleepFileDecoderRegistry().decoderFor(source("empty.json", "{\"sleep\":[]}")))
    }

    @Test
    fun fitbitUsesDeviceZonePreservesIdAndOverlaysShortWakeData() {
        val decoded = FitbitSleepFileDecoder.decode(
            resourceStream("fitbit-synthetic.json"),
            ZoneId.of("Europe/Riga"),
        )

        assertEquals(1, decoded.sessions.size)
        assertEquals(0, decoded.skippedRecordCount)
        val session = decoded.sessions.single()
        assertEquals("9000000000000000123", session.sourceId)
        assertEquals(ZoneOffset.ofHours(3), session.startZoneOffset)
        assertTrue(session.usedFallbackZone)
        assertEquals(3, session.stages.size)
        assertEquals(SleepFileStageType.SLEEPING, session.stages[0].type)
        assertEquals(SleepFileStageType.AWAKE, session.stages[1].type)
        assertEquals(SleepFileStageType.SLEEPING, session.stages[2].type)
        session.stages.zipWithNext().forEach { (first, second) ->
            assertFalse(first.endTime > second.startTime)
        }

        val healthRecord = session.toHealthConnectRecord()
        assertEquals(
            "darkhour:file:fitbit:9000000000000000123",
            healthRecord.metadata.clientRecordId,
        )
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, healthRecord.metadata.recordingMethod)
        assertEquals("Imported from Fitbit", healthRecord.title)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, healthRecord.stages[1].stage)
    }

    @Test
    fun fitbitTakeoutTopLevelArrayIsDetectedAndDecoded() {
        val source = resourceSource("takeout-without-extension", "fitbit-takeout-synthetic.json")

        val decoder = SleepFileDecoderRegistry().decoderFor(source)
        val decoded = FitbitSleepFileDecoder.decode(
            resourceStream("fitbit-takeout-synthetic.json"),
            ZoneId.of("Europe/Riga"),
        )

        assertEquals("Fitbit", decoder?.formatName)
        assertEquals(1, decoded.sessions.size)
        assertEquals("9000000000000000789", decoded.sessions.single().sourceId)
        assertEquals(3, decoded.sessions.single().stages.size)
    }

    @Test
    fun googleHealthPreservesInstantsOffsetsVersionAndManualMetadata() {
        val decoded = GoogleHealthSleepFileDecoder.decode(
            resourceStream("google-health-synthetic.json"),
            ZoneId.of("UTC"),
        )

        val session = decoded.sessions.single()
        assertEquals(
            "users/0000000000000000000/dataTypes/sleep/dataPoints/synthetic-manual-001",
            session.sourceId,
        )
        assertEquals(Instant.parse("2026-01-01T20:00:00Z"), session.startTime)
        assertEquals(ZoneOffset.ofHours(3), session.startZoneOffset)
        assertEquals(Instant.parse("2026-01-02T01:00:00Z").toEpochMilli(), session.clientRecordVersion)
        assertFalse(session.usedFallbackZone)
        assertEquals(SleepFileStageType.AWAKE, session.stages[1].type)

        val healthRecord = session.toHealthConnectRecord()
        assertEquals(
            "darkhour:file:google-health:users/0000000000000000000/dataTypes/sleep/" +
                "dataPoints/synthetic-manual-001",
            healthRecord.metadata.clientRecordId,
        )
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, healthRecord.metadata.recordingMethod)
        assertEquals("Synthetic Tracker", healthRecord.metadata.device?.model)
    }

    @Test
    fun invalidSessionsAreSkippedWhileValidSessionsRemain() {
        val decoded = FitbitSleepFileDecoder.decode(
            resourceStream("fitbit-partial-invalid-synthetic.json"),
            ZoneId.of("UTC"),
        )

        assertEquals(1, decoded.sessions.size)
        assertEquals(1, decoded.skippedRecordCount)
        assertTrue(decoded.issues.any { it.recordIndex == 1 })
    }

    @Test
    fun idlessSessionsUseStableCanonicalFingerprint() {
        val session = DecodedSleepSession(
            formatKey = "future",
            formatName = "Future",
            sourceId = null,
            clientRecordVersion = 0,
            startTime = Instant.parse("2026-01-01T00:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T01:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            stages = emptyList(),
            recordingMethod = SleepFileRecordingMethod.UNKNOWN,
        )

        val first = session.toHealthConnectRecord().metadata.clientRecordId
        val second = session.toHealthConnectRecord().metadata.clientRecordId

        assertEquals(first, second)
        assertTrue(first?.startsWith("darkhour:file:future:sha256:") == true)
    }

    @Test
    fun writerUsesHundredRecordBatchesAndStopsAfterFailure() {
        val records = List(205) { index ->
            DecodedSleepSession(
                formatKey = "test",
                formatName = "Test",
                sourceId = index.toString(),
                clientRecordVersion = 0,
                startTime = Instant.EPOCH.plusSeconds(index * 7200L),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.EPOCH.plusSeconds(index * 7200L + 3600),
                endZoneOffset = ZoneOffset.UTC,
                stages = emptyList(),
                recordingMethod = SleepFileRecordingMethod.UNKNOWN,
            ).toHealthConnectRecord()
        }
        val batchSizes = mutableListOf<Int>()
        var committed = 0

        runBlocking {
            writeSleepRecordsInBatches(
                records = records,
                insert = { batch -> batchSizes += batch.size },
                onBatchCommitted = { committed += it },
            )
        }

        assertEquals(listOf(100, 100, 5), batchSizes)
        assertEquals(205, committed)

        var failedCommitted = 0
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                writeSleepRecordsInBatches(
                    records = records,
                    insert = { batch ->
                        calls += 1
                        if (calls == 2) throw IOException("provider failed")
                        assertEquals(100, batch.size)
                    },
                    onBatchCommitted = { failedCommitted += it },
                )
            }
        }
        assertEquals(100, failedCommitted)
        assertEquals(2, calls)
    }

    private fun source(name: String, content: String) = SleepFileSource(name) {
        ByteArrayInputStream(content.toByteArray())
    }

    private fun resourceSource(displayName: String, resourceName: String) = SleepFileSource(displayName) {
        resourceStream(resourceName)
    }

    private fun resourceStream(name: String) = checkNotNull(
        javaClass.getResourceAsStream("/sleep-import/$name"),
    ) { "Missing synthetic test resource: $name" }
}
