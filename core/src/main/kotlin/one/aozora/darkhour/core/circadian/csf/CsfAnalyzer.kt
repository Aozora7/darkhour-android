package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.splitIntoSegments
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate
import java.time.ZoneOffset

fun analyzeCircadianCsf(
    records: List<SleepRecord>,
    smoothing: CsfSmoothingConfig,
    extraDays: Int = 0,
    config: CsfConfig = CsfConfig.Default,
): CsfAnalysis {
    if (records.isEmpty()) {
        return mergeSegmentResults(emptyList(), LocalDate.ofEpochDay(0))
    }

    val sorted = records.sortedBy { it.startTime }
    val analysisOffset = sorted.first().startZoneOffset
        ?: sorted.first().endZoneOffset
        ?: ZoneOffset.UTC
    val globalFirstDate = sorted.first().dateOfSleep
    val globalFirstDateMs = globalFirstDate.startInstant(analysisOffset).toEpochMilli()
    val segments = splitIntoSegments(sorted)
    val results = segments.mapIndexedNotNull { index, segment ->
        val segmentExtraDays = if (index == segments.lastIndex) extraDays else 0
        analyzeSegment(segment, segmentExtraDays, globalFirstDate, globalFirstDateMs, smoothing, config)
    }

    return mergeSegmentResults(results, globalFirstDate)
}
