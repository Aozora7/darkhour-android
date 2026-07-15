package one.aozora.darkhour.data

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDate

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
    fun healthConnectExportPreservesSchemaAndPortableIdentity() {
        val source = resourceSource("misleading.txt", "health-connect-synthetic.json")

        val decoder = SleepFileDecoderRegistry().decoderFor(source)
        val decoded = HealthConnectSleepFileDecoder.decode(
            resourceStream("health-connect-synthetic.json"),
            ZoneId.of("UTC"),
        )

        assertEquals("Health Connect", decoder?.formatName)
        val session = decoded.sessions.single()
        assertEquals("com.example.synthetic.health", session.sourcePackageName)
        assertEquals("synthetic-health-connect-id", session.sourceHealthConnectRecordId)
        assertEquals("synthetic-portable-id", session.sourceClientRecordId)
        assertEquals(42L, session.sourceClientRecordVersion)
        assertEquals(SleepFileRecordingMethod.ACTIVE, session.recordingMethod)
        assertEquals(7, session.stages.first().healthConnectStageType)
        assertNull(session.endZoneOffset)

        val restored = session.toHealthConnectRecord("one.aozora.darkhour.debug")
        assertTrue(
            restored.metadata.clientRecordId
                ?.startsWith("darkhour:file:health-connect:sha256:") == true,
        )
        assertEquals(Instant.parse("2026-07-15T05:00:00Z").toEpochMilli(), restored.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, restored.metadata.recordingMethod)
        assertEquals("Synthetic source title", restored.title)
        assertEquals("Synthetic source notes", restored.notes)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, restored.stages.first().stage)
    }

    @Test
    fun healthConnectOwnedRecordsPreserveClientIdentityAndVersion() {
        val session = HealthConnectSleepFileDecoder.decode(
            resourceStream("health-connect-synthetic.json"),
            ZoneId.of("UTC"),
        ).sessions.single().copy(sourcePackageName = "one.aozora.darkhour.debug")

        val restored = session.toHealthConnectRecord("one.aozora.darkhour.debug")

        assertEquals("synthetic-portable-id", restored.metadata.clientRecordId)
        assertEquals(42, restored.metadata.clientRecordVersion)
    }

    @Test
    fun healthConnectRestoreMatchesOnlySamePackageExactIdentitiesOrTimes() {
        val session = HealthConnectSleepFileDecoder.decode(
            resourceStream("health-connect-synthetic.json"),
            ZoneId.of("UTC"),
        ).sessions.single()
        fun existing(
            packageName: String = checkNotNull(session.sourcePackageName),
            recordId: String? = null,
            clientRecordId: String? = null,
            startTime: Instant = session.startTime.plusSeconds(1),
            endTime: Instant = session.endTime,
        ) = ExistingHealthConnectSourceRecord(
            packageName,
            recordId,
            clientRecordId,
            startTime,
            endTime,
        )

        assertTrue(session.matchesExistingSourceRecord(existing(recordId = "synthetic-health-connect-id")))
        assertTrue(session.matchesExistingSourceRecord(existing(clientRecordId = "synthetic-portable-id")))
        assertTrue(session.matchesExistingSourceRecord(existing(startTime = session.startTime)))
        assertFalse(session.matchesExistingSourceRecord(existing(packageName = "example.other", startTime = session.startTime)))
        assertFalse(session.matchesExistingSourceRecord(existing()))
    }

    @Test
    fun healthConnectUnsupportedSchemaIsRecognizedButRejected() {
        val unsupported = resourceText("health-connect-synthetic.json")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val source = source("future.json", unsupported)

        assertEquals("Health Connect", SleepFileDecoderRegistry().decoderFor(source)?.formatName)
        val decoded = HealthConnectSleepFileDecoder.decode(unsupported.byteInputStream(), ZoneId.of("UTC"))
        assertTrue(decoded.sessions.isEmpty())
        assertTrue(decoded.issues.any { "Unsupported" in it.reason })
    }

    @Test
    fun healthConnectSerializerWritesRecordFieldsAndExactStageType() {
        val start = Instant.parse("2026-07-14T20:00:00Z")
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.ofHours(3),
            endTime = start.plusSeconds(3600),
            endZoneOffset = null,
            title = "Synthetic title",
            notes = "Synthetic notes",
            metadata = Metadata.manualEntry(
                clientRecordId = "synthetic-client",
                clientRecordVersion = 3,
            ),
            stages = listOf(
                SleepSessionRecord.Stage(
                    startTime = start,
                    endTime = start.plusSeconds(3600),
                    stage = SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                ),
            ),
        )
        val output = StringWriter()
        JsonWriter(output).use { writer -> writer.writeSleepSession(record) }

        val json = output.toString()
        assertTrue("\"title\":\"Synthetic title\"" in json)
        assertTrue("\"notes\":\"Synthetic notes\"" in json)
        assertTrue("\"stage\":3" in json)
        assertTrue("\"clientRecordId\":\"synthetic-client\"" in json)
        assertTrue("\"endZoneOffset\":null" in json)
    }

    @Test
    fun exportRangeUsesInclusiveLocalDatesAcrossDst() {
        val range = SleepExportRange(
            startDate = LocalDate.parse("2026-03-29"),
            endDate = LocalDate.parse("2026-03-29"),
            zoneId = ZoneId.of("Europe/Riga"),
        )

        assertEquals(23, Duration.between(range.startInstant, range.endExclusiveInstant).toHours())
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
    fun directImportReturnsEverySessionWithoutReadingExistingRecords() {
        val session = DecodedSleepSession(
            formatKey = HEALTH_CONNECT_FORMAT_KEY,
            formatName = "Health Connect",
            sourceId = "source-id",
            clientRecordVersion = 1,
            startTime = Instant.parse("2026-01-01T00:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T01:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            stages = emptyList(),
            recordingMethod = SleepFileRecordingMethod.UNKNOWN,
        )
        var existingReadCount = 0

        val selected = runBlocking {
            selectSleepSessionsForImport(
                sessions = listOf(session),
                isHealthConnectExport = true,
                verifyExistingHealthConnectRecords = false,
                readExisting = {
                    existingReadCount += 1
                    setOf(session)
                },
            )
        }

        assertEquals(listOf(session), selected)
        assertEquals(0, existingReadCount)
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

    private fun resourceText(name: String): String = resourceStream(name).bufferedReader().use { it.readText() }
}
