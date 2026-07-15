package one.aozora.darkhour.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal object GoogleHealthSleepFileDecoder : SleepFileDecoder {
    override val formatName: String = "Google Health"

    override fun detects(input: InputStream): Boolean {
        val shape = detectJsonSleepRecordShape(input) ?: return false
        return shape.recordKeys.containsAll(setOf("name", "dataSource", "sleep")) &&
            "interval" in shape.nestedSleepKeys
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
    val endTime: String?,
    val type: String?,
)

private data class RawGoogleHealthRecord(
    val name: String?,
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
    var dataSource = RawGoogleHealthDataSource(null, null, null)
    var sleep = RawGoogleHealthSleep(null, null, null, null, null, emptyList())
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "name" -> name = nextStringOrNull()
            "dataSource" -> dataSource = readGoogleHealthDataSource()
            "sleep" -> sleep = readGoogleHealthSleep()
            else -> skipValue()
        }
    }
    endObject()
    return RawGoogleHealthRecord(
        name = name,
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
            var endTime: String? = null
            var type: String? = null
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "startTime" -> startTime = nextStringOrNull()
                    "endTime" -> endTime = nextStringOrNull()
                    "type" -> type = nextStringOrNull()
                    else -> skipValue()
                }
            }
            endObject()
            add(RawGoogleHealthStage(startTime, endTime, type))
        }
        endArray()
    }
}

private fun RawGoogleHealthRecord.toDecodedSession(
    fallbackZoneId: ZoneId,
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): DecodedSleepSession? {
    val start = startTime.parseInstant()
    val end = endTime.parseInstant()
    if (start == null || end == null || start >= end) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid sleep interval"))
        return null
    }
    val parsedStartOffset = startOffset.parseSecondsOffset()
    val parsedEndOffset = endOffset.parseSecondsOffset()
    val usedFallbackZone = parsedStartOffset == null || parsedEndOffset == null
    val resolvedStartOffset = parsedStartOffset ?: fallbackZoneId.rules.getOffset(start)
    val resolvedEndOffset = parsedEndOffset ?: fallbackZoneId.rules.getOffset(end)
    var unsupportedStage = false
    val rawStages = stages.mapIndexedNotNull { index, stage ->
        val stageStart = stage.startTime.parseInstant()
        val stageEnd = stage.endTime.parseInstant()
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
        sourceId = name?.takeIf(String::isNotBlank),
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

private fun String?.parseSecondsOffset(): ZoneOffset? {
    val seconds = this?.removeSuffix("s")?.toIntOrNull() ?: return null
    return runCatching { ZoneOffset.ofTotalSeconds(seconds) }.getOrNull()
}

private fun String?.toGoogleSleepFileStageType(): SleepFileStageType? = when (this?.uppercase()) {
    "DEEP" -> SleepFileStageType.DEEP
    "LIGHT" -> SleepFileStageType.LIGHT
    "REM" -> SleepFileStageType.REM
    "AWAKE", "WAKE", "RESTLESS" -> SleepFileStageType.AWAKE
    "ASLEEP", "SLEEPING", "UNKNOWN" -> SleepFileStageType.SLEEPING
    else -> null
}
