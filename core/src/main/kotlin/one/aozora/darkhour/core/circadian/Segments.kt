package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.model.SleepRecord
import java.time.temporal.ChronoUnit

fun splitIntoSegments(records: List<SleepRecord>): List<List<SleepRecord>> {
    if (records.isEmpty()) return emptyList()

    val sorted = records.sortedBy { it.startTime }
    val segments = mutableListOf(mutableListOf(sorted.first()))
    var latestDate = sorted.first().dateOfSleep

    for (record in sorted.drop(1)) {
        val gapDays = ChronoUnit.DAYS.between(latestDate, record.dateOfSleep).toInt()
        if (gapDays > GAP_THRESHOLD_DAYS) {
            segments += mutableListOf(record)
        } else {
            segments.last() += record
        }
        if (record.dateOfSleep > latestDate) {
            latestDate = record.dateOfSleep
        }
    }

    return segments.map { it.toList() }
}
