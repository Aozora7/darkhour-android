package one.aozora.darkhour.ui.actogram

import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import one.aozora.darkhour.ui.ActogramOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

data class ActogramStageBlock(
    val startHour: Double,
    val endHour: Double,
    val level: SleepStageLevel,
)

data class ActogramSleepBlock(
    val startHour: Double,
    val endHour: Double,
    val sleepScore: Double?,
    val isMainSleep: Boolean,
    val stages: List<ActogramStageBlock>,
    val selection: ActogramSelection.Sleep,
)

data class ActogramOverlayBlock(
    val startHour: Double,
    val endHour: Double,
    val confidence: Double,
    val isForecast: Boolean,
    val isGap: Boolean,
    val selection: ActogramSelection.Circadian,
)

sealed interface ActogramSelection {
    data class Sleep(
        val logId: Long,
        val startTime: Instant,
        val endTime: Instant,
        val startZoneOffset: ZoneOffset,
        val endZoneOffset: ZoneOffset,
        val sleepScore: Double?,
        val stages: SleepStages?,
        val isMainSleep: Boolean,
    ) : ActogramSelection

    data class Circadian(
        val date: LocalDate,
        val startTime: Instant,
        val endTime: Instant,
        val zoneOffset: ZoneOffset,
        val confidence: Double,
        val isForecast: Boolean,
    ) : ActogramSelection
}

data class ActogramRow(
    val date: LocalDate,
    val label: String,
    val startTime: Instant,
    val sleeps: List<ActogramSleepBlock>,
    val overlays: List<ActogramOverlayBlock>,
)

data class ActogramLayout(
    val rows: List<ActogramRow>,
    val rowHours: Double,
    val hasRealData: Boolean,
    val zoneOffset: ZoneOffset,
)

internal fun ActogramLayout.rowsForDisplay(
    order: ActogramOrder,
    minimumRows: Int,
): List<ActogramRow> {
    if (rows.isEmpty() || minimumRows <= rows.size) {
        return if (order == ActogramOrder.NEWEST_FIRST) rows.asReversed() else rows
    }

    val missingRows = minimumRows - rows.size
    val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
    val fillerRows = when (order) {
        ActogramOrder.NEWEST_FIRST -> {
            val oldest = rows.first()
            (1..missingRows).map { distance ->
                emptyRow(oldest.startTime.minusMillis(distance * rowDurationMs))
            }
        }

        ActogramOrder.OLDEST_FIRST -> {
            val newest = rows.last()
            (1..missingRows).map { distance ->
                emptyRow(newest.startTime.plusMillis(distance * rowDurationMs))
            }
        }
    }
    val orderedRows = if (order == ActogramOrder.NEWEST_FIRST) rows.asReversed() else rows
    return orderedRows + fillerRows
}

private fun ActogramLayout.emptyRow(startTime: Instant): ActogramRow {
    val localStart = startTime.atOffset(zoneOffset)
    val date = localStart.toLocalDate()
    val label = if (kotlin.math.abs(rowHours - 24.0) < 0.0001) {
        date.format(ActogramLayoutEngine.DateFormatter)
    } else {
        localStart.format(ActogramLayoutEngine.RowDateTimeFormatter)
    }
    return ActogramRow(
        date = date,
        label = label,
        startTime = startTime,
        sleeps = emptyList(),
        overlays = emptyList(),
    )
}

object ActogramLayoutEngine {
    internal val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
    internal val RowDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

    fun build(
        records: List<SleepRecord>,
        circadianDays: List<CircadianDay> = emptyList(),
        rowHours: Double = 24.0,
        minimumRows: Int = 7,
        referenceDate: LocalDate = LocalDate.now(),
    ): ActogramLayout {
        require(rowHours > 0.0)
        require(minimumRows > 0)

        return if (kotlin.math.abs(rowHours - 24.0) < 0.0001) {
            buildCalendarRows(records, circadianDays, minimumRows, referenceDate)
        } else {
            buildFixedDurationRows(records, circadianDays, rowHours, minimumRows, referenceDate)
        }
    }

    private fun buildCalendarRows(
        records: List<SleepRecord>,
        circadianDays: List<CircadianDay>,
        minimumRows: Int,
        referenceDate: LocalDate,
    ): ActogramLayout {
        val recordDates = records.flatMap { record ->
            val startOffset = record.startZoneOffset ?: ZoneOffset.UTC
            val endOffset = record.endZoneOffset ?: startOffset
            listOf(
                record.dateOfSleep,
                record.startTime.atOffset(startOffset).toLocalDate(),
                record.endTime.atOffset(endOffset).toLocalDate(),
            )
        }
        val offsetByDate = records
            .groupBy { it.dateOfSleep }
            .mapValues { (_, values) -> values.firstNotNullOfOrNull { it.startZoneOffset } }
        val fallbackOffset = records.firstNotNullOfOrNull { it.startZoneOffset } ?: ZoneOffset.UTC
        val circadianDates = circadianDays.flatMap { day ->
            if (day.isGap) {
                listOf(day.date)
            } else {
                day.coveredCalendarDates()
            }
        }
        val allDates = recordDates + circadianDates
        val lastDate = allDates.maxOrNull() ?: referenceDate
        val naturalFirstDate = allDates.minOrNull() ?: lastDate
        val paddedFirstDate = lastDate.minusDays((minimumRows - 1).toLong())
        val firstDate = minOf(naturalFirstDate, paddedFirstDate)

        val rows = generateSequence(firstDate) { current ->
            if (current < lastDate) current.plusDays(1) else null
        }.map { date ->
            val offset = offsetByDate[date] ?: fallbackOffset
            val rowStart = date.atStartOfDay().toInstant(offset)
            val rowEnd = date.plusDays(1).atStartOfDay().toInstant(offset)
            val sleeps = records.mapNotNull { record ->
                record.toBlock(rowStart, rowEnd)
            }
            val overlays = circadianDays.flatMap { circadian ->
                circadian.toCalendarOverlays(
                    rowStart = rowStart,
                    rowEnd = rowEnd,
                    dayMidnight = circadian.date.atStartOfDay().toInstant(offset),
                    zoneOffset = offset,
                )
            }
            ActogramRow(
                date = date,
                label = date.format(DateFormatter),
                startTime = rowStart,
                sleeps = sleeps,
                overlays = overlays,
            )
        }.toList()

        return ActogramLayout(
            rows = rows,
            rowHours = 24.0,
            hasRealData = records.isNotEmpty(),
            zoneOffset = fallbackOffset,
        )
    }

    private fun buildFixedDurationRows(
        records: List<SleepRecord>,
        circadianDays: List<CircadianDay>,
        rowHours: Double,
        minimumRows: Int,
        referenceDate: LocalDate,
    ): ActogramLayout {
        val fallbackOffset = records.firstNotNullOfOrNull { it.startZoneOffset } ?: ZoneOffset.UTC
        val firstInstant = records.minOfOrNull { it.startTime }
        val firstDate = firstInstant?.atOffset(fallbackOffset)?.toLocalDate()
            ?: circadianDays.minOfOrNull { it.date }
            ?: referenceDate
        val origin = firstDate.atStartOfDay().toInstant(fallbackOffset)
        val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
        val lastRecordInstant = records.maxOfOrNull { it.endTime }
        val lastCircadianInstant = circadianDays.maxOfOrNull { day ->
            day.date.plusDays(1).atStartOfDay().toInstant(fallbackOffset)
        }
        val naturalEnd = listOfNotNull(lastRecordInstant, lastCircadianInstant).maxOrNull()
            ?: origin.plusMillis(rowDurationMs)
        val naturalCount = ceil(
            Duration.between(origin, naturalEnd).toMillis().coerceAtLeast(1L).toDouble() / rowDurationMs,
        ).toInt()
        val rowCount = maxOf(minimumRows, naturalCount)
        val circadianByDate = circadianDays.associateBy { it.date }

        val rows = (0 until rowCount).map { index ->
            val rowStart = origin.plusMillis(index * rowDurationMs)
            val rowEnd = rowStart.plusMillis(rowDurationMs)
            val localStart = rowStart.atOffset(fallbackOffset)
            val date = localStart.toLocalDate()
            val label = if (localStart.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                date.format(DateFormatter)
            } else {
                localStart.format(RowDateTimeFormatter)
            }
            val overlayDate = date
            val circadian = circadianByDate[overlayDate]
            val circadianMidnight = overlayDate.atStartOfDay().toInstant(fallbackOffset)

            ActogramRow(
                date = date,
                label = label,
                startTime = rowStart,
                sleeps = records.mapNotNull { it.toBlock(rowStart, rowEnd) },
                overlays = circadian?.toFixedDurationOverlays(rowStart, rowEnd, circadianMidnight, fallbackOffset)
                    ?: emptyList(),
            )
        }

        return ActogramLayout(
            rows = rows,
            rowHours = rowHours,
            hasRealData = records.isNotEmpty(),
            zoneOffset = fallbackOffset,
        )
    }

    private fun CircadianDay.toCalendarOverlays(
        rowStart: Instant,
        rowEnd: Instant,
        dayMidnight: Instant,
        zoneOffset: ZoneOffset,
    ): List<ActogramOverlayBlock> {
        if (isGap) return emptyList()

        val nightStart = dayMidnight.plusMillis((nightStartHour * 3_600_000.0).roundToLong())
        var nightEnd = dayMidnight.plusMillis((nightEndHour * 3_600_000.0).roundToLong())
        while (nightEnd <= nightStart) {
            nightEnd = nightEnd.plus(Duration.ofDays(1))
        }
        val selection = ActogramSelection.Circadian(
            date = date,
            startTime = nightStart,
            endTime = nightEnd,
            zoneOffset = zoneOffset,
            confidence = confidenceScore,
            isForecast = isForecast,
        )

        val clippedStart = maxOf(nightStart, rowStart)
        val clippedEnd = minOf(nightEnd, rowEnd)
        if (clippedEnd <= clippedStart) return emptyList()

        return listOf(
            ActogramOverlayBlock(
                startHour = hoursBetween(rowStart, clippedStart),
                endHour = hoursBetween(rowStart, clippedEnd),
                confidence = confidenceScore,
                isForecast = isForecast,
                isGap = false,
                selection = selection,
            ),
        )
    }

    private fun CircadianDay.coveredCalendarDates(): List<LocalDate> {
        var endHour = nightEndHour
        while (endHour <= nightStartHour) {
            endHour += 24.0
        }
        val firstOffset = floor(nightStartHour / 24.0).toLong()
        val lastOffset = floor((endHour - 0.000001) / 24.0).toLong()
        return (firstOffset..lastOffset).map { date.plusDays(it) }
    }

    private fun CircadianDay.toFixedDurationOverlays(
        rowStart: Instant,
        rowEnd: Instant,
        dayMidnight: Instant,
        zoneOffset: ZoneOffset,
    ): List<ActogramOverlayBlock> {
        if (isGap) return emptyList()

        val nightStart = dayMidnight.plusMillis((nightStartHour * 3_600_000.0).roundToLong())
        var nightEnd = dayMidnight.plusMillis((nightEndHour * 3_600_000.0).roundToLong())
        while (nightEnd <= nightStart) {
            nightEnd = nightEnd.plus(Duration.ofDays(1))
        }
        val clippedStart = maxOf(nightStart, rowStart)
        val clippedEnd = minOf(nightEnd, rowEnd)
        if (clippedEnd <= clippedStart) return emptyList()

        return listOf(ActogramOverlayBlock(
            startHour = hoursBetween(rowStart, clippedStart),
            endHour = hoursBetween(rowStart, clippedEnd),
            confidence = confidenceScore,
            isForecast = isForecast,
            isGap = false,
            selection = ActogramSelection.Circadian(
                date = date,
                startTime = nightStart,
                endTime = nightEnd,
                zoneOffset = zoneOffset,
                confidence = confidenceScore,
                isForecast = isForecast,
            ),
        ))
    }

    private fun SleepRecord.toBlock(rowStart: Instant, rowEnd: Instant): ActogramSleepBlock? {
        val clippedStart = maxOf(startTime, rowStart)
        val clippedEnd = minOf(endTime, rowEnd)
        if (clippedEnd <= clippedStart) return null

        val stages = stageData.mapNotNull { stage ->
            val stageStart = maxOf(stage.startTime, rowStart)
            val stageEnd = minOf(stage.startTime.plusSeconds(stage.seconds.toLong()), rowEnd)
            if (stageEnd <= stageStart) {
                null
            } else {
                ActogramStageBlock(
                    startHour = hoursBetween(rowStart, stageStart),
                    endHour = hoursBetween(rowStart, stageEnd),
                    level = stage.level,
                )
            }
        }

        return ActogramSleepBlock(
            startHour = hoursBetween(rowStart, clippedStart),
            endHour = hoursBetween(rowStart, clippedEnd),
            sleepScore = sleepScore,
            isMainSleep = isMainSleep,
            stages = stages,
            selection = ActogramSelection.Sleep(
                logId = logId,
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = startZoneOffset ?: ZoneOffset.UTC,
                endZoneOffset = endZoneOffset ?: startZoneOffset ?: ZoneOffset.UTC,
                sleepScore = sleepScore,
                stages = stageSummaryOrNull(),
                isMainSleep = isMainSleep,
            ),
        )
    }

    private fun SleepRecord.stageSummaryOrNull(): SleepStages? {
        stages?.let { return it }
        if (stageData.isEmpty()) return null

        val minutesByStage = stageData
            .groupBy { it.level }
            .mapValues { (_, intervals) -> intervals.sumOf { it.seconds } / 60 }
        return SleepStages(
            deep = minutesByStage[SleepStageLevel.DEEP] ?: 0,
            light = minutesByStage[SleepStageLevel.LIGHT] ?: 0,
            rem = minutesByStage[SleepStageLevel.REM] ?: 0,
            wake = minutesByStage[SleepStageLevel.WAKE] ?: minutesAwake,
        )
    }

    private fun hoursBetween(start: Instant, end: Instant): Double =
        Duration.between(start, end).toMillis() / 3_600_000.0

}
