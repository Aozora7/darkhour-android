package one.aozora.darkhour.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

internal suspend fun importSleepFilesToHealthConnect(
    client: HealthConnectClient,
    contentResolver: ContentResolver,
    uris: List<Uri>,
    fallbackZoneId: ZoneId,
    callingPackageName: String? = null,
    registry: SleepFileDecoderRegistry = SleepFileDecoderRegistry(),
    verifyExistingHealthConnectRecords: Boolean = true,
): SleepFileImportResult {
    var recognizedFiles = 0
    var processedRecords = 0
    var committedRecords = 0
    var skippedRecords = 0
    var fallbackZoneRecords = 0
    var existingRecords = 0
    val issues = mutableListOf<String>()

    for (uri in uris) {
        val displayName = contentResolver.displayName(uri)
        val source = SleepFileSource(displayName) {
            contentResolver.openInputStream(uri)
                ?: error("The selected file could not be opened")
        }
        val decoder = registry.decoderFor(source)
        if (decoder == null) {
            issues += "$displayName: unsupported or ambiguous sleep file format"
            continue
        }
        recognizedFiles += 1
        val decoded = runCatching {
            source.openStream().use { decoder.decode(it, fallbackZoneId) }
        }.getOrElse { failure ->
            issues += "$displayName: ${failure.message ?: "could not be read"}"
            continue
        }
        processedRecords += decoded.sessions.size
        skippedRecords += decoded.skippedRecordCount
        fallbackZoneRecords += decoded.fallbackZoneRecordCount
        decoded.issues.firstOrNull()?.let { issue ->
            val location = issue.recordIndex?.let { "record ${it + 1}: " }.orEmpty()
            issues += "$displayName: $location${issue.reason}"
        }

        val sessionsToWrite = runCatching {
            selectSleepSessionsForImport(
                sessions = decoded.sessions,
                isHealthConnectExport = decoder === HealthConnectSleepFileDecoder,
                verifyExistingHealthConnectRecords = verifyExistingHealthConnectRecords,
                readExisting = client::readExistingSourceMatches,
            )
        }.getOrElse { failure ->
            return SleepFileImportResult(
                selectedFileCount = uris.size,
                recognizedFileCount = recognizedFiles,
                processedRecordCount = processedRecords,
                committedRecordCount = committedRecords,
                skippedRecordCount = skippedRecords,
                fallbackZoneRecordCount = fallbackZoneRecords,
                issues = issues.take(MAX_IMPORT_RESULT_ISSUES),
                errorMessage = failure.message ?: "Could not verify existing Health Connect records",
            )
        }
        val existingInFile = decoded.sessions.size - sessionsToWrite.size
        skippedRecords += existingInFile
        existingRecords += existingInFile
        val records = sessionsToWrite.map { session ->
            session.toHealthConnectRecord(callingPackageName)
        }
        val failure = runCatching {
            writeSleepRecordsInBatches(
                records = records,
                insert = client::insertRecords,
                onBatchCommitted = { count -> committedRecords += count },
            )
        }.exceptionOrNull()
        if (failure != null) {
            return SleepFileImportResult(
                selectedFileCount = uris.size,
                recognizedFileCount = recognizedFiles,
                processedRecordCount = processedRecords,
                committedRecordCount = committedRecords,
                skippedRecordCount = skippedRecords,
                fallbackZoneRecordCount = fallbackZoneRecords,
                existingRecordCount = existingRecords,
                issues = issues.take(MAX_IMPORT_RESULT_ISSUES),
                errorMessage = failure.message ?: "Health Connect rejected a write batch",
            )
        }
    }

    return SleepFileImportResult(
        selectedFileCount = uris.size,
        recognizedFileCount = recognizedFiles,
        processedRecordCount = processedRecords,
        committedRecordCount = committedRecords,
        skippedRecordCount = skippedRecords,
        fallbackZoneRecordCount = fallbackZoneRecords,
        existingRecordCount = existingRecords,
        issues = issues.take(MAX_IMPORT_RESULT_ISSUES),
    )
}

internal suspend fun selectSleepSessionsForImport(
    sessions: List<DecodedSleepSession>,
    isHealthConnectExport: Boolean,
    verifyExistingHealthConnectRecords: Boolean,
    readExisting: suspend (List<DecodedSleepSession>) -> Set<DecodedSleepSession>,
): List<DecodedSleepSession> {
    if (!isHealthConnectExport || !verifyExistingHealthConnectRecords) return sessions
    val existing = readExisting(sessions)
    return sessions.filterNot(existing::contains)
}

internal suspend fun HealthConnectClient.readExistingSourceMatches(
    sessions: List<DecodedSleepSession>,
): Set<DecodedSleepSession> {
    val sourceSessions = sessions.filter {
        it.formatKey == HEALTH_CONNECT_FORMAT_KEY && !it.sourcePackageName.isNullOrBlank()
    }
    if (sourceSessions.isEmpty()) return emptySet()
    val start = sourceSessions.minOf(DecodedSleepSession::startTime)
    val end = sourceSessions.maxOf(DecodedSleepSession::endTime)
    val origins = sourceSessions.mapNotNullTo(linkedSetOf()) { session ->
        session.sourcePackageName?.let(::DataOrigin)
    }
    val existing = readSleepRecordsPageRange(start, end, origins)
    return sourceSessions.filterTo(linkedSetOf()) { source ->
        existing.any { target ->
            source.matchesExistingSourceRecord(
                ExistingHealthConnectSourceRecord(
                    packageName = target.metadata.dataOrigin.packageName,
                    recordId = target.metadata.id,
                    clientRecordId = target.metadata.clientRecordId,
                    startTime = target.startTime,
                    endTime = target.endTime,
                ),
            )
        }
    }
}

internal data class ExistingHealthConnectSourceRecord(
    val packageName: String,
    val recordId: String?,
    val clientRecordId: String?,
    val startTime: Instant,
    val endTime: Instant,
)

internal fun DecodedSleepSession.matchesExistingSourceRecord(
    existing: ExistingHealthConnectSourceRecord,
): Boolean {
    if (formatKey != HEALTH_CONNECT_FORMAT_KEY || sourcePackageName != existing.packageName) return false
    return when {
        !sourceHealthConnectRecordId.isNullOrBlank() &&
            existing.recordId == sourceHealthConnectRecordId -> true
        !sourceClientRecordId.isNullOrBlank() &&
            existing.clientRecordId == sourceClientRecordId -> true
        else -> existing.startTime == startTime && existing.endTime == endTime
    }
}

internal suspend fun writeSleepRecordsInBatches(
    records: List<SleepSessionRecord>,
    insert: suspend (List<SleepSessionRecord>) -> Unit,
    onBatchCommitted: (Int) -> Unit = {},
) {
    records.chunked(SLEEP_FILE_WRITE_BATCH_SIZE).forEach { batch ->
        insert(batch)
        onBatchCommitted(batch.size)
    }
}

internal fun DecodedSleepSession.toHealthConnectRecord(
    callingPackageName: String? = null,
): SleepSessionRecord {
    val clientRecordId = targetClientRecordId(callingPackageName)
    val healthDevice = device?.let { source ->
        Device(
            type = source.type,
            manufacturer = source.manufacturer,
            model = source.model,
        )
    }
    val metadata = when (recordingMethod) {
        SleepFileRecordingMethod.ACTIVE -> healthDevice?.let { activeDevice ->
            Metadata.activelyRecorded(
                device = activeDevice,
                clientRecordId = clientRecordId,
                clientRecordVersion = targetClientRecordVersion(callingPackageName),
            )
        } ?: Metadata.unknownRecordingMethod(
            clientRecordId = clientRecordId,
            clientRecordVersion = targetClientRecordVersion(callingPackageName),
        )
        SleepFileRecordingMethod.AUTOMATIC -> Metadata.autoRecorded(
            device = healthDevice ?: Device(type = Device.TYPE_UNKNOWN),
            clientRecordId = clientRecordId,
            clientRecordVersion = targetClientRecordVersion(callingPackageName),
        )
        SleepFileRecordingMethod.MANUAL -> Metadata.manualEntry(
            clientRecordId = clientRecordId,
            clientRecordVersion = targetClientRecordVersion(callingPackageName),
            device = healthDevice,
        )
        SleepFileRecordingMethod.UNKNOWN -> Metadata.unknownRecordingMethod(
            clientRecordId = clientRecordId,
            clientRecordVersion = targetClientRecordVersion(callingPackageName),
            device = healthDevice,
        )
    }
    return SleepSessionRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        title = if (formatKey == HEALTH_CONNECT_FORMAT_KEY) title else "Imported from $formatName",
        notes = if (formatKey == HEALTH_CONNECT_FORMAT_KEY) notes else null,
        stages = stages.map { stage ->
            SleepSessionRecord.Stage(
                startTime = stage.startTime,
                endTime = stage.endTime,
                stage = stage.healthConnectStageType ?: when (stage.type) {
                    SleepFileStageType.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                    SleepFileStageType.SLEEPING -> SleepSessionRecord.STAGE_TYPE_SLEEPING
                    SleepFileStageType.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                    SleepFileStageType.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                    SleepFileStageType.REM -> SleepSessionRecord.STAGE_TYPE_REM
                },
            )
        },
    )
}

private fun DecodedSleepSession.targetClientRecordVersion(callingPackageName: String?): Long =
    if (formatKey == HEALTH_CONNECT_FORMAT_KEY && sourcePackageName == callingPackageName) {
        sourceClientRecordVersion ?: clientRecordVersion
    } else {
        clientRecordVersion
    }

private fun DecodedSleepSession.targetClientRecordId(callingPackageName: String?): String {
    if (
        formatKey == HEALTH_CONNECT_FORMAT_KEY &&
        sourcePackageName == callingPackageName &&
        !sourceClientRecordId.isNullOrBlank()
    ) {
        return checkNotNull(sourceClientRecordId)
    }
    if (formatKey == HEALTH_CONNECT_FORMAT_KEY) {
        val sourceIdentity = sourceClientRecordId?.takeIf(String::isNotBlank)
            ?: sourceHealthConnectRecordId?.takeIf(String::isNotBlank)
            ?: canonicalFingerprint()
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${sourcePackageName.orEmpty()}\u0000$sourceIdentity".toByteArray(StandardCharsets.UTF_8),
        )
        return "$SLEEP_FILE_CLIENT_RECORD_PREFIX$HEALTH_CONNECT_FORMAT_KEY:sha256:" +
            digest.joinToString("") { byte -> "%02x".format(byte) }
    }
    return "$SLEEP_FILE_CLIENT_RECORD_PREFIX$formatKey:${sourceId ?: canonicalFingerprint()}"
}

internal const val SLEEP_FILE_CLIENT_RECORD_PREFIX = "darkhour:file:"

private fun DecodedSleepSession.canonicalFingerprint(): String {
    val canonical = buildString {
        append(startTime.toEpochMilli()).append('|')
        append(startZoneOffset?.totalSeconds).append('|')
        append(endTime.toEpochMilli()).append('|')
        append(endZoneOffset?.totalSeconds)
        stages.forEach { stage ->
            append('|').append(stage.startTime.toEpochMilli())
            append(':').append(stage.endTime.toEpochMilli())
            append(':').append(stage.type.name)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return "sha256:${digest.joinToString("") { byte -> "%02x".format(byte) }}"
}

private fun ContentResolver.displayName(uri: Uri): String {
    val queried = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queried?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "selected file"
}

internal const val SLEEP_FILE_WRITE_BATCH_SIZE = 100
private const val MAX_IMPORT_RESULT_ISSUES = 5
