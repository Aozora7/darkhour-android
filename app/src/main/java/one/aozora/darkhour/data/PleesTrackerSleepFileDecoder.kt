package one.aozora.darkhour.data

import androidx.health.connect.client.records.metadata.Device
import org.apache.commons.csv.CSVFormat
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

internal object PleesTrackerSleepFileDecoder : SleepFileDecoder {
    override val format = SleepFileFormatInfo(
        key = "plees-tracker",
        name = "Plees Tracker",
        description = "Plees Tracker CSV backups containing sleep times, ratings, comments, and wakes.",
        fileExtensions = setOf("csv"),
        mimeTypes = setOf("text/csv", "application/csv", "text/comma-separated-values"),
    )

    override fun detects(input: InputStream): Boolean = runCatching {
        input.readPleesTrackerCsv().firstOrNull()?.isPleesTrackerHeader() == true
    }.getOrDefault(false)

    override fun decode(input: InputStream, fallbackZoneId: ZoneId): DecodedSleepFile {
        val sessions = mutableListOf<DecodedSleepSession>()
        val issues = mutableListOf<SleepFileIssue>()
        var skipped = 0
        val rows = runCatching(input::readPleesTrackerCsv).getOrElse { failure ->
            return DecodedSleepFile(
                sessions = emptyList(),
                skippedRecordCount = 0,
                issues = listOf(
                    SleepFileIssue(
                        recordIndex = null,
                        reason = "Malformed Plees Tracker CSV: ${failure.message ?: failure::class.simpleName}",
                    ),
                ),
            )
        }
        val header = rows.firstOrNull()
        if (header == null || !header.isPleesTrackerHeader()) {
            return DecodedSleepFile(
                sessions = emptyList(),
                skippedRecordCount = 0,
                issues = listOf(SleepFileIssue(null, "Invalid Plees Tracker CSV header")),
            )
        }
        val columns = header.withIndex().associate { (index, name) -> name to index }
        rows.drop(1).forEachIndexed { recordIndex, row ->
            val decoded = row.toPleesTrackerSession(columns, recordIndex, issues)
            if (decoded == null) skipped += 1 else sessions += decoded
        }
        return DecodedSleepFile(sessions, skipped, issues)
    }
}

private fun List<String>.isPleesTrackerHeader(): Boolean =
    toSet().containsAll(PLEES_TRACKER_REQUIRED_COLUMNS)

private fun List<String>.toPleesTrackerSession(
    columns: Map<String, Int>,
    recordIndex: Int,
    issues: MutableList<SleepFileIssue>,
): DecodedSleepSession? {
    fun value(name: String): String? = columns[name]?.let(::getOrNull)

    val startMillis = value("start").toPleesTrackerEpochMillis()
    val endMillis = value("stop").toPleesTrackerEpochMillis()
    val start = startMillis?.let { millis -> runCatching { Instant.ofEpochMilli(millis) }.getOrNull() }
    val end = endMillis?.let { millis -> runCatching { Instant.ofEpochMilli(millis) }.getOrNull() }
    if (start == null || end == null || start >= end) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid start or stop time"))
        return null
    }
    val ratingValue = value("rating")
    val rating = ratingValue?.takeIf(String::isNotBlank)?.toLongOrNull() ?: 0
    if (!ratingValue.isNullOrBlank() && ratingValue.toLongOrNull() == null) {
        issues.addBounded(SleepFileIssue(recordIndex, "Invalid rating"))
        return null
    }
    val wakes = value("wakes")?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
    val comment = value("comment").orEmpty()
    return DecodedSleepSession(
        formatKey = "plees-tracker",
        formatName = "Plees Tracker",
        sourceId = value("sid")?.takeIf(String::isNotBlank),
        clientRecordVersion = 0,
        startTime = start,
        startZoneOffset = null,
        endTime = end,
        endZoneOffset = null,
        stages = listOf(
            SleepFileStage(
                startTime = start,
                endTime = end,
                type = SleepFileStageType.SLEEPING,
            ),
        ),
        recordingMethod = SleepFileRecordingMethod.ACTIVE,
        device = SleepFileDevice(type = Device.TYPE_PHONE),
        notes = pleesTrackerNotes(comment, rating, wakes),
    )
}

private fun String?.toPleesTrackerEpochMillis(): Long? = this?.toLongOrNull()?.let { value ->
    if (value < PLEES_TRACKER_MILLISECONDS_CUTOFF) {
        runCatching { Math.multiplyExact(value, 1_000) }.getOrNull()
    } else {
        value
    }
}

private fun pleesTrackerNotes(comment: String, rating: Long, wakes: Int): String {
    if (rating == 0L && wakes == 0) return comment
    val metadata = "Plees Tracker metadata v1\nRating: $rating\nWakes: $wakes"
    return if (comment.isEmpty()) metadata else "$comment\n\n$metadata"
}

private fun InputStream.readPleesTrackerCsv(): List<List<String>> =
    CSVFormat.DEFAULT.parse(InputStreamReader(this, StandardCharsets.UTF_8)).use { parser ->
        val rows = parser.map { record -> record.toList() }.toMutableList()
        rows.firstOrNull()?.firstOrNull()?.let { firstCell ->
            if (firstCell.startsWith('\uFEFF')) {
                rows[0] = rows[0].toMutableList().also { it[0] = firstCell.removePrefix("\uFEFF") }
            }
        }
        rows
    }

private const val PLEES_TRACKER_MILLISECONDS_CUTOFF = 1_000_000_000_000L
private val PLEES_TRACKER_REQUIRED_COLUMNS = setOf("sid", "start", "stop")
