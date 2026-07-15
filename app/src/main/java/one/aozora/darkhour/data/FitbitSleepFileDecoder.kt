package one.aozora.darkhour.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object FitbitSleepFileDecoder : SleepFileDecoder {
    override val formatName: String = "Fitbit"

    override fun detects(input: InputStream): Boolean {
        val shape = detectJsonSleepRecordShape(input) ?: return false
        return shape.recordKeys.containsAll(setOf("logId", "dateOfSleep", "levels"))
    }

    override fun decode(input: InputStream, fallbackZoneId: ZoneId): DecodedSleepFile {
        val sessions = mutableListOf<DecodedSleepSession>()
        val issues = mutableListOf<SleepFileIssue>()
        var skipped = 0
        runCatching {
            input.jsonReader().use { reader ->
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        skipped += reader.readFitbitSleepArray(sessions, issues, fallbackZoneId)
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() != "sleep") {
                                reader.skipValue()
                                continue
                            }
                            skipped += reader.readFitbitSleepArray(sessions, issues, fallbackZoneId)
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
                    reason = "Malformed Fitbit JSON: ${failure.message ?: failure::class.simpleName}",
                ),
            )
        }
        return DecodedSleepFile(sessions, skipped, issues)
    }

    private fun JsonReader.readFitbitSleepArray(
        sessions: MutableList<DecodedSleepSession>,
        issues: MutableList<SleepFileIssue>,
        fallbackZoneId: ZoneId,
    ): Int {
        if (peek() != JsonToken.BEGIN_ARRAY) error("Expected a sleep array")
        var skipped = 0
        beginArray()
        var recordIndex = 0
        while (hasNext()) {
            val raw = readFitbitSleep()
            val decoded = raw.toDecodedSession(fallbackZoneId, recordIndex, issues)
            if (decoded == null) skipped += 1 else sessions += decoded
            recordIndex += 1
        }
        endArray()
        return skipped
    }
}

private data class RawFitbitStage(
    val dateTime: String?,
    val level: String?,
    val seconds: Long?,
    val priority: Int,
)

private data class RawFitbitSleep(
    val startTime: String?,
    val endTime: String?,
    val durationMillis: Long?,
    val logId: String?,
    val logType: String?,
    val stages: List<RawFitbitStage>,
)

private fun JsonReader.readFitbitSleep(): RawFitbitSleep {
    var startTime: String? = null
    var endTime: String? = null
    var durationMillis: Long? = null
    var logId: String? = null
    var logType: String? = null
    var stages = emptyList<RawFitbitStage>()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "startTime" -> startTime = nextStringOrNull()
            "endTime" -> endTime = nextStringOrNull()
            "duration" -> durationMillis = nextLongOrNull()
            "logId" -> logId = nextStringOrNull()
            "logType" -> logType = nextStringOrNull()
            "levels" -> stages = readFitbitLevels()
            else -> skipValue()
        }
    }
    endObject()
    return RawFitbitSleep(startTime, endTime, durationMillis, logId, logType, stages)
}

private fun JsonReader.readFitbitLevels(): List<RawFitbitStage> {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return emptyList()
    }
    val stages = mutableListOf<RawFitbitStage>()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "data" -> readFitbitStageArray(priority = 0, destination = stages)
            "shortData" -> readFitbitStageArray(priority = 1, destination = stages)
            else -> skipValue()
        }
    }
    endObject()
    return stages
}

private fun JsonReader.readFitbitStageArray(
    priority: Int,
    destination: MutableList<RawFitbitStage>,
) {
    if (peek() != JsonToken.BEGIN_ARRAY) {
        skipValue()
        return
    }
    beginArray()
    while (hasNext()) {
        var dateTime: String? = null
        var level: String? = null
        var seconds: Long? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "dateTime" -> dateTime = nextStringOrNull()
                "level" -> level = nextStringOrNull()
                "seconds" -> seconds = nextLongOrNull()
                else -> skipValue()
            }
        }
        endObject()
        destination += RawFitbitStage(dateTime, level, seconds, priority)
    }
    endArray()
}

private fun RawFitbitSleep.toDecodedSession(
    zoneId: ZoneId,
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): DecodedSleepSession? {
    val startLocal = startTime.parseFitbitLocalDateTime()
    if (startLocal == null) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid startTime"))
        return null
    }
    val startZoned = startLocal.atZone(zoneId)
    val start = startZoned.toInstant()
    val endLocal = endTime.parseFitbitLocalDateTime()
    val end = endLocal?.atZone(zoneId)?.toInstant()
        ?: durationMillis?.takeIf { it > 0 }?.let(start::plusMillis)
    if (end == null || start >= end) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid endTime"))
        return null
    }
    val endOffset = endLocal?.atZone(zoneId)?.offset ?: zoneId.rules.getOffset(end)
    var unsupportedStage = false
    val rawStages = stages.mapIndexedNotNull { index, stage ->
        val stageStart = stage.dateTime.parseFitbitLocalDateTime()?.atZone(zoneId)?.toInstant()
        val seconds = stage.seconds
        val type = stage.level.toSleepFileStageType()
        if (type == null && stage.level != null) unsupportedStage = true
        if (stageStart == null || seconds == null || seconds <= 0 || type == null) {
            null
        } else {
            SleepFileStage(
                startTime = stageStart,
                endTime = stageStart.plusSeconds(seconds),
                type = type,
                priority = stage.priority,
                sourceOrder = index,
            )
        }
    }
    if (unsupportedStage) {
        issues.addBounded(SleepFileIssue(recordIndex, "Ignored an unsupported sleep stage"))
    }
    return DecodedSleepSession(
        formatKey = "fitbit",
        formatName = "Fitbit",
        sourceId = logId?.takeIf(String::isNotBlank),
        clientRecordVersion = 0,
        startTime = start,
        startZoneOffset = startZoned.offset,
        endTime = end,
        endZoneOffset = endOffset,
        stages = normalizeSleepFileStages(start, end, rawStages),
        recordingMethod = if (logType.equals("manual", ignoreCase = true)) {
            SleepFileRecordingMethod.MANUAL
        } else {
            SleepFileRecordingMethod.AUTOMATIC
        },
        device = SleepFileDevice(
            type = androidx.health.connect.client.records.metadata.Device.TYPE_FITNESS_BAND,
            manufacturer = "Fitbit",
        ),
        usedFallbackZone = true,
    )
}

private fun String?.parseFitbitLocalDateTime(): LocalDateTime? =
    this?.let { value -> runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.getOrNull() }

private fun String?.toSleepFileStageType(): SleepFileStageType? = when (this?.uppercase()) {
    "DEEP" -> SleepFileStageType.DEEP
    "LIGHT" -> SleepFileStageType.LIGHT
    "REM" -> SleepFileStageType.REM
    "AWAKE", "WAKE", "RESTLESS" -> SleepFileStageType.AWAKE
    "ASLEEP", "SLEEPING", "UNKNOWN" -> SleepFileStageType.SLEEPING
    else -> null
}

internal fun MutableList<SleepFileIssue>.addBounded(issue: SleepFileIssue) {
    if (size < MAX_SLEEP_FILE_ISSUES) add(issue)
}

private const val MAX_SLEEP_FILE_ISSUES = 10
