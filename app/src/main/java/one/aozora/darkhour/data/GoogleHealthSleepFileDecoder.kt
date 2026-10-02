package one.aozora.darkhour.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object GoogleHealthSleepFileDecoder : SleepFileDecoder {
    override val format = SleepFileFormatInfo(
        key = "google-health",
        name = "Google Health",
        description = "Google Health API sleep session JSON. Exported by 25h.aozora.one.",
        fileExtensions = setOf("json"),
        mimeTypes = setOf("application/json", "text/json"),
    )

    override fun detects(input: InputStream): Boolean {
        val shape = detectJsonSleepRecordShape(input) ?: return false
        // Only `dataPoints:list` responses carry `dataSource`. Dark Hour's export
        // writes the `dataPoints:reconcile` shape, which has the resource name and
        // the nested interval but no data source at all.
        val hasResourceName = "name" in shape.recordKeys || "dataPointName" in shape.recordKeys
        return hasResourceName && "sleep" in shape.recordKeys && "interval" in shape.nestedSleepKeys
    }

    override fun decode(input: InputStream, fallbackZoneId: ZoneId): DecodedSleepFile {
        val sessions = mutableListOf<DecodedSleepSession>()
        val issues = mutableListOf<SleepFileIssue>()
        var skipped = 0
        runCatching {
            input.jsonReader().use { reader ->
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        skipped += reader.readGoogleHealthSleepArray(sessions, issues, fallbackZoneId)
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() != "sleep") {
                                reader.skipValue()
                                continue
                            }
                            skipped += reader.readGoogleHealthSleepArray(
                                sessions,
                                issues,
                                fallbackZoneId,
                            )
                        }
                        reader.endObject()
                    }
                    else -> error("Expected a JSON object or array")
                }
            }
        }.onFailure { failure ->
            issues.addBounded(
                SleepFileIssue(
                    recordIndex = null,
                    reason = "Malformed Google Health JSON: ${failure.message ?: failure::class.simpleName}",
                ),
            )
        }
        return DecodedSleepFile(sessions, skipped, issues)
    }
}

private fun JsonReader.readGoogleHealthSleepArray(
    sessions: MutableList<DecodedSleepSession>,
    issues: MutableList<SleepFileIssue>,
    fallbackZoneId: ZoneId,
): Int {
    if (peek() != JsonToken.BEGIN_ARRAY) error("Expected a sleep array")
    var skipped = 0
    beginArray()
    var recordIndex = 0
    while (hasNext()) {
        val raw = readGoogleHealthRecord()
        val decoded = raw.toDecodedSession(fallbackZoneId, recordIndex, issues)
        if (decoded == null) skipped += 1 else sessions += decoded
        recordIndex += 1
    }
    endArray()
    return skipped
}

private data class RawGoogleHealthStage(
    val startTime: String?,
    val startOffset: String?,
    val endTime: String?,
    val endOffset: String?,
    val type: String?,
)

private data class RawGoogleHealthRecord(
    val name: String?,
    val dataPointName: String?,
    val recordingMethod: String?,
    val platform: String?,
    val deviceDisplayName: String?,
    val startTime: String?,
    val startOffset: String?,
    val endTime: String?,
    val endOffset: String?,
    val updateTime: String?,
    val stages: List<RawGoogleHealthStage>,
)

private data class RawGoogleHealthDataSource(
    val recordingMethod: String?,
    val platform: String?,
    val deviceDisplayName: String?,
)

private data class RawGoogleHealthSleep(
    val startTime: String?,
    val startOffset: String?,
    val endTime: String?,
    val endOffset: String?,
    val updateTime: String?,
    val stages: List<RawGoogleHealthStage>,
)

private fun JsonReader.readGoogleHealthRecord(): RawGoogleHealthRecord {
    var name: String? = null
    var dataPointName: String? = null
    var dataSource = RawGoogleHealthDataSource(null, null, null)
    var sleep = RawGoogleHealthSleep(null, null, null, null, null, emptyList())
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "name" -> name = nextStringOrNull()
            "dataPointName" -> dataPointName = nextStringOrNull()
            "dataSource" -> dataSource = readGoogleHealthDataSource()
            "sleep" -> sleep = readGoogleHealthSleep()
            else -> skipValue()
        }
    }
    endObject()
    return RawGoogleHealthRecord(
        name = name,
        dataPointName = dataPointName,
        recordingMethod = dataSource.recordingMethod,
        platform = dataSource.platform,
        deviceDisplayName = dataSource.deviceDisplayName,
        startTime = sleep.startTime,
        startOffset = sleep.startOffset,
        endTime = sleep.endTime,
        endOffset = sleep.endOffset,
        updateTime = sleep.updateTime,
        stages = sleep.stages,
    )
}

private fun JsonReader.readGoogleHealthDataSource(): RawGoogleHealthDataSource {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return RawGoogleHealthDataSource(null, null, null)
    }
    var recordingMethod: String? = null
    var platform: String? = null
    var deviceDisplayName: String? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "recordingMethod" -> recordingMethod = nextStringOrNull()
            "platform" -> platform = nextStringOrNull()
            "device" -> {
                val device = readStringObject()
                deviceDisplayName = device["displayName"]
            }
            else -> skipValue()
        }
    }
    endObject()
    return RawGoogleHealthDataSource(recordingMethod, platform, deviceDisplayName)
}

private fun JsonReader.readGoogleHealthSleep(): RawGoogleHealthSleep {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return RawGoogleHealthSleep(null, null, null, null, null, emptyList())
    }
    var startTime: String? = null
    var startOffset: String? = null
    var endTime: String? = null
    var endOffset: String? = null
    var updateTime: String? = null
    var stages = emptyList<RawGoogleHealthStage>()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "interval" -> {
                val interval = readStringObject()
                startTime = interval["startTime"]
                startOffset = interval["startUtcOffset"]
                endTime = interval["endTime"]
                endOffset = interval["endUtcOffset"]
            }
            "stages" -> stages = readGoogleHealthStages()
            "updateTime" -> updateTime = nextStringOrNull()
            else -> skipValue()
        }
    }
    endObject()
    return RawGoogleHealthSleep(startTime, startOffset, endTime, endOffset, updateTime, stages)
}

private fun JsonReader.readGoogleHealthStages(): List<RawGoogleHealthStage> {
    if (peek() != JsonToken.BEGIN_ARRAY) {
        skipValue()
        return emptyList()
    }
    return buildList {
        beginArray()
        while (hasNext()) {
            var startTime: String? = null
            var startOffset: String? = null
            var endTime: String? = null
            var endOffset: String? = null
            var type: String? = null
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "startTime" -> startTime = nextStringOrNull()
                    "startUtcOffset" -> startOffset = nextStringOrNull()
                    "endTime" -> endTime = nextStringOrNull()
                    "endUtcOffset" -> endOffset = nextStringOrNull()
                    "type" -> type = nextStringOrNull()
                    else -> skipValue()
                }
            }
            endObject()
            add(RawGoogleHealthStage(startTime, startOffset, endTime, endOffset, type))
        }
        endArray()
    }
}

private fun RawGoogleHealthRecord.toDecodedSession(
    fallbackZoneId: ZoneId,
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): DecodedSleepSession? {
    val parsedStartOffset = startOffset.parseSecondsOffset()
    // `endUtcOffset` is missing on some responses. The recorded start offset is
    // the zone the data was captured in, so it beats the device fallback zone.
    val parsedEndOffset = endOffset.parseSecondsOffset() ?: parsedStartOffset
    val start = startTime.resolveInstant(parsedStartOffset, fallbackZoneId)
    val end = endTime.resolveInstant(parsedEndOffset, fallbackZoneId)
    if (start == null || end == null || start >= end) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid sleep interval"))
        return null
    }
    val resolvedStartOffset = parsedStartOffset ?: fallbackZoneId.rules.getOffset(start)
    val resolvedEndOffset = parsedEndOffset ?: fallbackZoneId.rules.getOffset(end)
    val usedFallbackZone = parsedStartOffset == null
    var unsupportedStage = false
    val rawStages = stages.mapIndexedNotNull { index, stage ->
        // Stage offsets are the authoritative zone for a zone-less stage
        // timestamp; the session interval supplies them when absent.
        val stageStartOffset = stage.startOffset.parseSecondsOffset() ?: parsedStartOffset
        val stageEndOffset = stage.endOffset.parseSecondsOffset() ?: stageStartOffset
        val stageStart = stage.startTime.resolveInstant(stageStartOffset, fallbackZoneId)
        val stageEnd = stage.endTime.resolveInstant(stageEndOffset, fallbackZoneId)
        val type = stage.type.toGoogleSleepFileStageType()
        if (type == null && stage.type != null) unsupportedStage = true
        if (stageStart == null || stageEnd == null || stageStart >= stageEnd || type == null) {
            null
        } else {
            SleepFileStage(
                startTime = stageStart,
                endTime = stageEnd,
                type = type,
                sourceOrder = index,
            )
        }
    }
    if (unsupportedStage) {
        issues.addBounded(SleepFileIssue(recordIndex, "Ignored an unsupported sleep stage"))
    }
    return DecodedSleepSession(
        formatKey = "google-health",
        formatName = "Google Health",
        sourceId = (name ?: dataPointName)?.takeIf(String::isNotBlank),
        clientRecordVersion = updateTime.parseInstant()?.toEpochMilli() ?: 0,
        startTime = start,
        startZoneOffset = resolvedStartOffset,
        endTime = end,
        endZoneOffset = resolvedEndOffset,
        stages = normalizeSleepFileStages(start, end, rawStages),
        recordingMethod = when (recordingMethod?.uppercase()) {
            "MANUAL" -> SleepFileRecordingMethod.MANUAL
            "DERIVED" -> SleepFileRecordingMethod.AUTOMATIC
            else -> SleepFileRecordingMethod.UNKNOWN
        },
        device = googleHealthDevice(platform, deviceDisplayName),
        usedFallbackZone = usedFallbackZone,
    )
}

private fun googleHealthDevice(platform: String?, displayName: String?): SleepFileDevice? {
    if (platform == null && displayName == null) return null
    val isFitbit = platform.equals("FITBIT", ignoreCase = true)
    return SleepFileDevice(
        type = if (isFitbit) {
            androidx.health.connect.client.records.metadata.Device.TYPE_FITNESS_BAND
        } else {
            androidx.health.connect.client.records.metadata.Device.TYPE_UNKNOWN
        },
        manufacturer = platform?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase),
        model = displayName,
    )
}

private fun String?.parseInstant(): Instant? =
    this?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

/**
 * Resolve a Google Health timestamp to an absolute instant.
 *
 * Dark Hour's export and most API responses carry absolute UTC instants, but the
 * Google Health API can also emit zone-less wall-clock strings such as
 * `"2022-05-13 22:23:30"`. Those are only meaningful next to the paired protobuf
 * `*UtcOffset` duration, which is the sole record of the zone the data was
 * captured in, so they are resolved against it rather than being handed to a
 * lenient parser that would guess the device zone.
 */
private fun String?.resolveInstant(offset: ZoneOffset?, fallbackZoneId: ZoneId): Instant? {
    val value = this?.trim()?.takeIf(String::isNotEmpty)?.replace(' ', 'T') ?: return null
    runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
    runCatching {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant()
    }.getOrNull()?.let { return it }
    val local = runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: return null
    return if (offset != null) local.toInstant(offset) else local.atZone(fallbackZoneId).toInstant()
}

/** Parse a protobuf `Duration` offset such as `"10800s"` for UTC+3. */
private fun String?.parseSecondsOffset(): ZoneOffset? {
    val seconds = this?.removeSuffix("s")?.toIntOrNull() ?: return null
    return runCatching { ZoneOffset.ofTotalSeconds(seconds) }.getOrNull()
}

private fun String?.toGoogleSleepFileStageType(): SleepFileStageType? = when (this?.uppercase()) {
    "DEEP" -> SleepFileStageType.DEEP
    "LIGHT" -> SleepFileStageType.LIGHT
    "REM" -> SleepFileStageType.REM
    "AWAKE", "WAKE", "RESTLESS", "OUT_OF_BED" -> SleepFileStageType.AWAKE
    "ASLEEP", "SLEEPING", "UNKNOWN" -> SleepFileStageType.SLEEPING
    else -> null
}
