package one.aozora.darkhour.data

import androidx.health.connect.client.records.metadata.Device
import java.io.InputStream
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
    size >= PLEES_TRACKER_COLUMNS.size && take(PLEES_TRACKER_COLUMNS.size) == PLEES_TRACKER_COLUMNS

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
    val rating = value("rating")?.toLongOrNull()
    if (rating == null) {
        issues.addBounded(SleepFileIssue(recordIndex, "Missing or invalid rating"))
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

/** Parses RFC 4180-style records, including escaped quotes and quoted line breaks. */
private fun InputStream.readPleesTrackerCsv(): List<List<String>> {
    val text = bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var index = 0
    var fieldStarted = false
    var inQuotes = false
    var closedQuote = false

    fun finishField() {
        row += field.toString()
        field.clear()
        fieldStarted = false
        closedQuote = false
    }

    fun finishRow() {
        finishField()
        rows += row
        row = mutableListOf()
    }

    while (index < text.length) {
        val character = text[index]
        if (inQuotes) {
            if (character == '"') {
                if (index + 1 < text.length && text[index + 1] == '"') {
                    field.append('"')
                    index += 2
                    continue
                }
                inQuotes = false
                closedQuote = true
            } else {
                field.append(character)
            }
            index += 1
            continue
        }
        if (closedQuote && character != ',' && character != '\r' && character != '\n') {
            error("Unexpected character after a closing quote")
        }
        when (character) {
            '"' -> {
                if (fieldStarted) error("Unexpected quote in an unquoted field")
                fieldStarted = true
                inQuotes = true
            }
            ',' -> finishField()
            '\r', '\n' -> {
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index += 1
                }
                if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) finishRow()
            }
            else -> {
                fieldStarted = true
                field.append(character)
            }
        }
        index += 1
    }
    if (inQuotes) error("Unterminated quoted field")
    if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) finishRow()
    rows.firstOrNull()?.firstOrNull()?.let { firstCell ->
        if (firstCell.startsWith('\uFEFF')) {
            rows[0] = rows[0].toMutableList().also { it[0] = firstCell.removePrefix("\uFEFF") }
        }
    }
    return rows
}

private const val PLEES_TRACKER_MILLISECONDS_CUTOFF = 1_000_000_000_000L
private val PLEES_TRACKER_COLUMNS = listOf("sid", "start", "stop", "rating", "comment", "wakes")
