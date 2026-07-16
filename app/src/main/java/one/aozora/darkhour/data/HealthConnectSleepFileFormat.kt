package one.aozora.darkhour.data

import android.content.ContentResolver
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal const val HEALTH_CONNECT_FILE_FORMAT = "one.aozora.darkhour.health-connect.sleep"
internal const val HEALTH_CONNECT_FILE_SCHEMA_VERSION = 1
internal const val HEALTH_CONNECT_FORMAT_KEY = "health-connect"

data class SleepExportRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: ZoneId,
) {
    init {
        require(!endDate.isBefore(startDate))
    }

    val startInstant: Instant
        get() = startDate.atStartOfDay(zoneId).toInstant()
    val endExclusiveInstant: Instant
        get() = endDate.plusDays(1).atStartOfDay(zoneId).toInstant()
}

data class SleepExportPackage(
    val packageName: String,
    val displayName: String,
    val recordCount: Int,
)

data class SleepExportPreparation(
    val range: SleepExportRange,
    val packages: List<SleepExportPackage>,
) {
    val recordCount: Int
        get() = packages.sumOf(SleepExportPackage::recordCount)
}

data class SleepExportResult(
    val exportedRecordCount: Int,
    val packageCount: Int,
) {
    fun summaryText(): String =
        "Exported $exportedRecordCount sleep records from $packageCount " +
            if (packageCount == 1) "package" else "packages"
}

internal suspend fun HealthConnectClient.prepareSleepExport(
    range: SleepExportRange,
    packageDisplayName: (String) -> String,
): SleepExportPreparation {
    val counts = linkedMapOf<String, Int>()
    var oldestStart: Instant? = null
    forEachSleepRecordPage(range, emptySet()) { records ->
        records.forEach { record ->
            oldestStart = minOf(oldestStart ?: record.startTime, record.startTime)
            val packageName = record.metadata.dataOrigin.packageName
            if (packageName.isNotBlank()) counts[packageName] = counts.getOrDefault(packageName, 0) + 1
        }
    }
    return SleepExportPreparation(
        range = if (range.startDate == LocalDate.ofEpochDay(0) && oldestStart != null) {
            range.copy(startDate = checkNotNull(oldestStart).atZone(range.zoneId).toLocalDate())
        } else {
            range
        },
        packages = counts.map { (packageName, count) ->
            SleepExportPackage(packageName, packageDisplayName(packageName), count)
        }.sortedWith(compareBy(SleepExportPackage::displayName, SleepExportPackage::packageName)),
    )
}

internal suspend fun HealthConnectClient.writeSleepExport(
    contentResolver: ContentResolver,
    uri: Uri,
    range: SleepExportRange,
    packageNames: Set<String>,
    clock: Clock = Clock.systemUTC(),
): SleepExportResult {
    require(packageNames.isNotEmpty())
    val stream = contentResolver.openOutputStream(uri, "wt")
        ?: error("The selected export file could not be opened")
    var exported = 0
    stream.use { output ->
        JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject()
            writer.name("format").value(HEALTH_CONNECT_FILE_FORMAT)
            writer.name("schemaVersion").value(HEALTH_CONNECT_FILE_SCHEMA_VERSION.toLong())
            writer.name("exportedAt").value(clock.instant().toString())
            writer.name("zoneId").value(range.zoneId.id)
            writer.name("startDate").value(range.startDate.toString())
            writer.name("endDate").value(range.endDate.toString())
            writer.name("sleep").beginArray()
            val origins = packageNames.mapTo(linkedSetOf()) { DataOrigin(it) }
            forEachSleepRecordPage(range, origins) { records ->
                records.forEach { record ->
                    writer.writeSleepSession(record)
                    exported += 1
                }
            }
            writer.endArray()
            writer.endObject()
        }
    }
    return SleepExportResult(exported, packageNames.size)
}

private suspend fun HealthConnectClient.forEachSleepRecordPage(
    range: SleepExportRange,
    origins: Set<DataOrigin>,
    consume: (List<SleepSessionRecord>) -> Unit,
) {
    val seenPageTokens = mutableSetOf<String>()
    var pageToken: String? = null
    do {
        val response = readRecordsWithRateLimitRetry(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(range.startInstant, range.endExclusiveInstant),
                dataOriginFilter = origins,
                ascendingOrder = true,
                pageSize = HEALTH_CONNECT_EXPORT_PAGE_SIZE,
                pageToken = pageToken,
            ),
        )
        consume(response.records)
        pageToken = nextHealthConnectPageToken(response.pageToken, seenPageTokens)
    } while (pageToken != null)
}

internal fun JsonWriter.writeSleepSession(record: SleepSessionRecord) {
    beginObject()
    name("startTime").value(record.startTime.toString())
    name("startZoneOffset").nullableValue(record.startZoneOffset?.toString())
    name("endTime").value(record.endTime.toString())
    name("endZoneOffset").nullableValue(record.endZoneOffset?.toString())
    name("title").nullableValue(record.title)
    name("notes").nullableValue(record.notes)
    name("stages").beginArray()
    record.stages.forEach { stage ->
        beginObject()
        name("startTime").value(stage.startTime.toString())
        name("endTime").value(stage.endTime.toString())
        name("stage").value(stage.stage.toLong())
        endObject()
    }
    endArray()
    name("metadata").beginObject()
    name("id").value(record.metadata.id)
    name("dataOrigin").beginObject()
    name("packageName").value(record.metadata.dataOrigin.packageName)
    endObject()
    name("lastModifiedTime").value(record.metadata.lastModifiedTime.toString())
    name("clientRecordId").nullableValue(record.metadata.clientRecordId)
    name("clientRecordVersion").value(record.metadata.clientRecordVersion)
    name("recordingMethod").value(record.metadata.recordingMethod.toLong())
    name("device")
    val device = record.metadata.device
    if (device == null) {
        nullValue()
    } else {
        beginObject()
        name("type").value(device.type.toLong())
        name("manufacturer").nullableValue(device.manufacturer)
        name("model").nullableValue(device.model)
        endObject()
    }
    endObject()
    endObject()
}

private fun JsonWriter.nullableValue(value: String?) {
    if (value == null) nullValue() else value(value)
}

internal object HealthConnectSleepFileDecoder : SleepFileDecoder {
    override val formatName: String = "Health Connect"

    override fun detects(input: InputStream): Boolean = runCatching {
        input.jsonReader().use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@use false
            var format: String? = null
            var version: Long? = null
            reader.beginObject()
            while (reader.hasNext() && (format == null || version == null)) {
                when (reader.nextName()) {
                    "format" -> format = reader.nextStringOrNull()
                    "schemaVersion" -> version = reader.nextLongOrNull()
                    else -> reader.skipValue()
                }
            }
            format == HEALTH_CONNECT_FILE_FORMAT
        }
    }.getOrDefault(false)

    override fun decode(input: InputStream, fallbackZoneId: ZoneId): DecodedSleepFile {
        val sessions = mutableListOf<DecodedSleepSession>()
        val issues = mutableListOf<SleepFileIssue>()
        var skipped = 0
        var format: String? = null
        var version: Long? = null
        runCatching {
            input.jsonReader().use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "format" -> format = reader.nextStringOrNull()
                        "schemaVersion" -> version = reader.nextLongOrNull()
                        "sleep" -> {
                            if (format != HEALTH_CONNECT_FILE_FORMAT ||
                                version != HEALTH_CONNECT_FILE_SCHEMA_VERSION.toLong()
                            ) {
                                error("Unsupported Health Connect export schema")
                            }
                            reader.beginArray()
                            var index = 0
                            while (reader.hasNext()) {
                                val decoded = reader.readHealthConnectSession(index, issues)
                                if (decoded == null) skipped += 1 else sessions += decoded
                                index += 1
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }.onFailure { failure ->
            issues.addBounded(
                SleepFileIssue(null, failure.message ?: "Malformed Health Connect export"),
            )
        }
        return DecodedSleepFile(sessions, skipped, issues)
    }
}

private data class RawHealthConnectMetadata(
    val id: String? = null,
    val packageName: String? = null,
    val lastModifiedTime: String? = null,
    val clientRecordId: String? = null,
    val clientRecordVersion: Long = 0,
    val recordingMethod: Int = Metadata.RECORDING_METHOD_UNKNOWN,
    val device: SleepFileDevice? = null,
)

private fun JsonReader.readHealthConnectSession(
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): DecodedSleepSession? {
    var startTime: String? = null
    var startOffset: String? = null
    var endTime: String? = null
    var endOffset: String? = null
    var title: String? = null
    var notes: String? = null
    var stages = emptyList<SleepFileStage>()
    var metadata = RawHealthConnectMetadata()
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        issues.addBounded(SleepFileIssue(recordIndex, "Expected a sleep record object"))
        return null
    }
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "startTime" -> startTime = nextStringOrNull()
            "startZoneOffset" -> startOffset = nextStringOrNull()
            "endTime" -> endTime = nextStringOrNull()
            "endZoneOffset" -> endOffset = nextStringOrNull()
            "title" -> title = nextStringOrNull()
            "notes" -> notes = nextStringOrNull()
            "stages" -> stages = readHealthConnectStages(recordIndex, issues)
            "metadata" -> metadata = readHealthConnectMetadata()
            else -> skipValue()
        }
    }
    endObject()
    val start = startTime.parseExportInstant()
    val end = endTime.parseExportInstant()
    if (start == null || end == null || !start.isBefore(end)) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid sleep interval"))
        return null
    }
    val lastModified = metadata.lastModifiedTime.parseExportInstant()?.toEpochMilli() ?: 0
    return DecodedSleepSession(
        formatKey = HEALTH_CONNECT_FORMAT_KEY,
        formatName = "Health Connect",
        sourceId = metadata.clientRecordId ?: metadata.id,
        clientRecordVersion = lastModified,
        startTime = start,
        startZoneOffset = startOffset.parseExportOffset(),
        endTime = end,
        endZoneOffset = endOffset.parseExportOffset(),
        stages = normalizeSleepFileStages(start, end, stages),
        recordingMethod = when (metadata.recordingMethod) {
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> SleepFileRecordingMethod.ACTIVE
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> SleepFileRecordingMethod.AUTOMATIC
            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> SleepFileRecordingMethod.MANUAL
            else -> SleepFileRecordingMethod.UNKNOWN
        },
        device = metadata.device,
        title = title,
        notes = notes,
        sourcePackageName = metadata.packageName,
        sourceHealthConnectRecordId = metadata.id,
        sourceClientRecordId = metadata.clientRecordId,
        sourceClientRecordVersion = metadata.clientRecordVersion,
    )
}

private fun JsonReader.readHealthConnectStages(
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): List<SleepFileStage> {
    if (peek() != JsonToken.BEGIN_ARRAY) {
        skipValue()
        return emptyList()
    }
    return buildList {
        beginArray()
        var order = 0
        while (hasNext()) {
            var start: String? = null
            var end: String? = null
            var type: Long? = null
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "startTime" -> start = nextStringOrNull()
                    "endTime" -> end = nextStringOrNull()
                    "stage" -> type = nextLongOrNull()
                    else -> skipValue()
                }
            }
            endObject()
            val startInstant = start.parseExportInstant()
            val endInstant = end.parseExportInstant()
            val stageType = type?.toInt()
            if (startInstant == null || endInstant == null || !startInstant.isBefore(endInstant) ||
                stageType == null || stageType !in HEALTH_CONNECT_SLEEP_STAGE_TYPES
            ) {
                issues.addBounded(SleepFileIssue(recordIndex, "Ignored an invalid sleep stage"))
            } else {
                add(
                    SleepFileStage(
                        startTime = startInstant,
                        endTime = endInstant,
                        type = stageType.toSleepFileStageType(),
                        sourceOrder = order,
                        healthConnectStageType = stageType,
                    ),
                )
            }
            order += 1
        }
        endArray()
    }
}

private fun JsonReader.readHealthConnectMetadata(): RawHealthConnectMetadata {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return RawHealthConnectMetadata()
    }
    var result = RawHealthConnectMetadata()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "id" -> result = result.copy(id = nextStringOrNull())
            "dataOrigin" -> {
                val origin = readStringObject()
                result = result.copy(packageName = origin["packageName"])
            }
            "lastModifiedTime" -> result = result.copy(lastModifiedTime = nextStringOrNull())
            "clientRecordId" -> result = result.copy(clientRecordId = nextStringOrNull())
            "clientRecordVersion" -> result = result.copy(clientRecordVersion = nextLongOrNull() ?: 0)
            "recordingMethod" -> result = result.copy(recordingMethod = nextLongOrNull()?.toInt() ?: 0)
            "device" -> result = result.copy(device = readHealthConnectDevice())
            else -> skipValue()
        }
    }
    endObject()
    return result
}

private fun JsonReader.readHealthConnectDevice(): SleepFileDevice? {
    if (peek() == JsonToken.NULL) {
        nextNull()
        return null
    }
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return null
    }
    var type = Device.TYPE_UNKNOWN
    var manufacturer: String? = null
    var model: String? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "type" -> type = nextLongOrNull()?.toInt() ?: Device.TYPE_UNKNOWN
            "manufacturer" -> manufacturer = nextStringOrNull()
            "model" -> model = nextStringOrNull()
            else -> skipValue()
        }
    }
    endObject()
    return SleepFileDevice(type, manufacturer, model)
}

private fun Int.toSleepFileStageType(): SleepFileStageType = when (this) {
    SleepSessionRecord.STAGE_TYPE_DEEP -> SleepFileStageType.DEEP
    SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepFileStageType.LIGHT
    SleepSessionRecord.STAGE_TYPE_REM -> SleepFileStageType.REM
    SleepSessionRecord.STAGE_TYPE_SLEEPING,
    SleepSessionRecord.STAGE_TYPE_UNKNOWN,
    -> SleepFileStageType.SLEEPING
    else -> SleepFileStageType.AWAKE
}

private fun String?.parseExportInstant(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun String?.parseExportOffset(): ZoneOffset? =
    this?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }

private val HEALTH_CONNECT_SLEEP_STAGE_TYPES = setOf(
    SleepSessionRecord.STAGE_TYPE_UNKNOWN,
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_SLEEPING,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    SleepSessionRecord.STAGE_TYPE_LIGHT,
    SleepSessionRecord.STAGE_TYPE_DEEP,
    SleepSessionRecord.STAGE_TYPE_REM,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
)

private const val HEALTH_CONNECT_EXPORT_PAGE_SIZE = 1000
