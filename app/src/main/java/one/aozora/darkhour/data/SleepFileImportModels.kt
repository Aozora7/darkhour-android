package one.aozora.darkhour.data

import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal enum class SleepFileRecordingMethod {
    ACTIVE,
    AUTOMATIC,
    MANUAL,
    UNKNOWN,
}

internal enum class SleepFileStageType {
    AWAKE,
    SLEEPING,
    LIGHT,
    DEEP,
    REM,
}

internal data class SleepFileDevice(
    val type: Int,
    val manufacturer: String? = null,
    val model: String? = null,
)

internal data class SleepFileStage(
    val startTime: Instant,
    val endTime: Instant,
    val type: SleepFileStageType,
    val priority: Int = 0,
    val sourceOrder: Int = 0,
    val healthConnectStageType: Int? = null,
)

internal data class DecodedSleepSession(
    val formatKey: String,
    val formatName: String,
    val sourceId: String?,
    val clientRecordVersion: Long,
    val startTime: Instant,
    val startZoneOffset: ZoneOffset?,
    val endTime: Instant,
    val endZoneOffset: ZoneOffset?,
    val stages: List<SleepFileStage>,
    val recordingMethod: SleepFileRecordingMethod,
    val device: SleepFileDevice? = null,
    val usedFallbackZone: Boolean = false,
    val title: String? = null,
    val notes: String? = null,
    val sourcePackageName: String? = null,
    val sourceHealthConnectRecordId: String? = null,
    val sourceClientRecordId: String? = null,
    val sourceClientRecordVersion: Long? = null,
)

internal data class SleepFileIssue(
    val recordIndex: Int?,
    val reason: String,
)

internal data class DecodedSleepFile(
    val sessions: List<DecodedSleepSession>,
    val skippedRecordCount: Int,
    val issues: List<SleepFileIssue>,
) {
    val fallbackZoneRecordCount: Int
        get() = sessions.count(DecodedSleepSession::usedFallbackZone)
}

internal data class SleepFileSource(
    val displayName: String,
    val openStream: () -> InputStream,
)

internal data class SleepFileFormatInfo(
    val key: String,
    val name: String,
    val description: String,
    val fileExtensions: Set<String>,
    val mimeTypes: Set<String>,
) {
    init {
        require(key.isNotBlank()) { "A sleep file format key is required" }
        require(name.isNotBlank()) { "A sleep file format name is required" }
        require(description.isNotBlank()) { "A sleep file format description is required" }
        require(fileExtensions.isNotEmpty()) { "At least one sleep file extension is required" }
        require(mimeTypes.isNotEmpty()) { "At least one sleep file MIME type is required" }
    }
}

internal interface SleepFileDecoder {
    val format: SleepFileFormatInfo

    val formatName: String
        get() = format.name

    fun detects(input: InputStream): Boolean

    fun decode(input: InputStream, fallbackZoneId: ZoneId): DecodedSleepFile
}

internal class SleepFileDecoderRegistry(
    private val decoders: List<SleepFileDecoder> = listOf(
        PleesTrackerSleepFileDecoder,
        FitbitSleepFileDecoder,
        GoogleHealthSleepFileDecoder,
        HealthConnectSleepFileDecoder,
    ),
) {
    val supportedFormats: List<SleepFileFormatInfo> = decoders.map(SleepFileDecoder::format)

    val supportedMimeTypes: Set<String> = supportedFormats
        .flatMapTo(linkedSetOf(), SleepFileFormatInfo::mimeTypes)

    fun decoderFor(source: SleepFileSource): SleepFileDecoder? = decoders.singleOrNull { decoder ->
        runCatching { source.openStream().use(decoder::detects) }.getOrDefault(false)
    }
}

enum class HealthConnectFileOperation {
    IDLE,
    PREPARING_EXPORT,
    EXPORTING,
    IMPORTING,
    DELETING,
}

data class SleepFileImportResult(
    val selectedFileCount: Int,
    val recognizedFileCount: Int,
    val processedRecordCount: Int,
    val committedRecordCount: Int,
    val skippedRecordCount: Int,
    val fallbackZoneRecordCount: Int,
    val existingRecordCount: Int = 0,
    val issues: List<String>,
    val errorMessage: String? = null,
) {
    fun summaryText(): String = buildString {
        if (processedRecordCount > 0 && existingRecordCount == processedRecordCount) {
            append("All $processedRecordCount sleep records already exist")
            return@buildString
        }
        append("Processed $processedRecordCount sleep records from ")
        append("$recognizedFileCount of $selectedFileCount files")
        if (skippedRecordCount > 0) append("; skipped $skippedRecordCount")
        if (fallbackZoneRecordCount > 0) {
            append("; used the device timezone for $fallbackZoneRecordCount")
        }
        if (committedRecordCount != processedRecordCount) {
            append("; committed $committedRecordCount")
        }
        if (existingRecordCount > 0) append("; already present $existingRecordCount")
    }
}

internal fun normalizeSleepFileStages(
    sessionStart: Instant,
    sessionEnd: Instant,
    stages: List<SleepFileStage>,
): List<SleepFileStage> {
    if (sessionStart >= sessionEnd || stages.isEmpty()) return emptyList()
    val clipped = stages.mapNotNull { stage ->
        val start = maxOf(sessionStart, stage.startTime)
        val end = minOf(sessionEnd, stage.endTime)
        if (start >= end) null else stage.copy(startTime = start, endTime = end)
    }
    if (clipped.isEmpty()) return emptyList()

    val boundaries = buildSet {
        clipped.forEach { stage ->
            add(stage.startTime)
            add(stage.endTime)
        }
    }.sorted()
    val normalized = mutableListOf<SleepFileStage>()
    boundaries.zipWithNext().forEach { (start, end) ->
        val winner = clipped
            .asSequence()
            .filter { it.startTime <= start && it.endTime >= end }
            .maxWithOrNull(
                compareBy<SleepFileStage> { it.priority }
                    .thenBy { -it.sourceOrder },
            )
            ?: return@forEach
        val previous = normalized.lastOrNull()
        if (
            previous != null &&
            previous.type == winner.type &&
            previous.healthConnectStageType == winner.healthConnectStageType &&
            previous.endTime == start
        ) {
            normalized[normalized.lastIndex] = previous.copy(endTime = end)
        } else {
            normalized += winner.copy(startTime = start, endTime = end)
        }
    }
    return normalized
}
