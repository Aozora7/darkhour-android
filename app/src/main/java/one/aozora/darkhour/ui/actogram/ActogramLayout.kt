package one.aozora.darkhour.ui.actogram

import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
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

data class ActogramScheduleBlock(
    val entryId: Long,
    val startHour: Double,
    val endHour: Double,
    val color: Long,
    val selection: ActogramSelection.Schedule,
)

data class ActogramLegendItem(
    val label: String,
    val color: Long,
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

    data class Schedule(
        val entry: ScheduleEntry,
        val occurrenceStart: Instant,
        val occurrenceEnd: Instant,
        val zoneOffset: ZoneOffset,
    ) : ActogramSelection
}

data class ActogramRow(
    val date: LocalDate,
    val label: String,
    val startTime: Instant,
    val sleeps: List<ActogramSleepBlock>,
    val overlays: List<ActogramOverlayBlock>,
    val schedules: List<ActogramScheduleBlock>,
    val kind: ActogramRowKind = ActogramRowKind.DATA,
    val legendItems: List<ActogramLegendItem> = emptyList(),
)

data class ActogramLayout(
    val rows: List<ActogramRow>,
    val rowHours: Double,
    val hasRealData: Boolean,
    val zoneOffset: ZoneOffset,
)

enum class ActogramRowKind {
    DATA,
    LEGEND_STAGES,
    LEGEND_OVERLAYS,
}

internal fun ActogramLayout.rowsForDisplay(
    order: ActogramOrder,
    minimumRows: Int,
    includeLegend: Boolean = false,
): List<ActogramRow> {
    val legendRows = if (includeLegend && hasRealData && rows.isNotEmpty()) {
        legendRows(scheduleLegendItems())
    } else {
        emptyList()
    }
    val minimumDataRows = (minimumRows - legendRows.size).coerceAtLeast(rows.size)
    if (rows.isEmpty() || minimumDataRows <= rows.size) {
        val orderedRows = if (order == ActogramOrder.NEWEST_FIRST) rows.asReversed() else rows
        return when (order) {
            ActogramOrder.NEWEST_FIRST -> orderedRows + legendRows
            ActogramOrder.OLDEST_FIRST -> legendRows + orderedRows
        }
    }

    val missingRows = minimumDataRows - rows.size
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
    return when (order) {
        ActogramOrder.NEWEST_FIRST -> orderedRows + fillerRows + legendRows
        ActogramOrder.OLDEST_FIRST -> legendRows + orderedRows + fillerRows
    }
}

private fun ActogramLayout.legendRows(scheduleItems: List<ActogramLegendItem>): List<ActogramRow> {
    val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
    return listOf(
        legendRow(
            startTime = rows.first().startTime.minusMillis(rowDurationMs * 2),
            label = "Stages",
            kind = ActogramRowKind.LEGEND_STAGES,
        ),
        legendRow(
            startTime = rows.first().startTime.minusMillis(rowDurationMs),
            label = "Overlays",
            kind = ActogramRowKind.LEGEND_OVERLAYS,
            legendItems = scheduleItems,
        ),
    )
}

private fun ActogramLayout.legendRow(
    startTime: Instant,
    label: String,
    kind: ActogramRowKind,
    legendItems: List<ActogramLegendItem> = emptyList(),
): ActogramRow {
    val localStart = startTime.atOffset(zoneOffset)
    return ActogramRow(
        date = localStart.toLocalDate(),
        label = label,
        startTime = startTime,
        sleeps = emptyList(),
        overlays = emptyList(),
        schedules = emptyList(),
        kind = kind,
        legendItems = legendItems,
    )
}

private fun ActogramLayout.scheduleLegendItems(): List<ActogramLegendItem> =
    rows.asSequence()
        .flatMap { it.schedules.asSequence() }
        .map { schedule ->
            ActogramLegendItem(
                label = schedule.selection.entry.label.ifBlank { "Schedule" },
                color = schedule.color,
            )
        }
        .distinctBy { it.label to it.color }
        .toList()

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
        schedules = emptyList(),
    )
}

object ActogramLayoutEngine {
    internal val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
    internal val RowDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

    fun build(
        records: List<SleepRecord>,
        circadianDays: List<CircadianDay> = emptyList(),
        scheduleEntries: List<ScheduleEntry> = emptyList(),
        rowHours: Double = 24.0,
        minimumRows: Int = 7,
        referenceDate: LocalDate = LocalDate.now(),
    ): ActogramLayout {
        require(rowHours > 0.0)
        require(minimumRows > 0)

        return if (kotlin.math.abs(rowHours - 24.0) < 0.0001) {
            buildCalendarRows(records, circadianDays, scheduleEntries, minimumRows, referenceDate)
        } else {
            buildFixedDurationRows(records, circadianDays, scheduleEntries, rowHours, minimumRows, referenceDate)
        }
    }

    fun withScheduleEntries(
        layout: ActogramLayout,
        scheduleEntries: List<ScheduleEntry>,
    ): ActogramLayout {
        if (layout.rows.isEmpty()) return layout
        val rowDurationMs = (layout.rowHours * 3_600_000.0).roundToLong()
        val rows = layout.rows.mapIndexed { index, row ->
            val rowEnd = layout.rows.getOrNull(index + 1)?.startTime
                ?: row.startTime.plusMillis(rowDurationMs)
            row.copy(
                schedules = scheduleEntries.toScheduleBlocks(
                    rowStart = row.startTime,
                    rowEnd = rowEnd,
                    zoneOffset = layout.zoneOffset,
                ),
            )
        }
        return layout.copy(rows = rows)
    }

    private fun buildCalendarRows(
        records: List<SleepRecord>,
        circadianDays: List<CircadianDay>,
        scheduleEntries: List<ScheduleEntry>,
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
        val scheduleDates = scheduleEntries.mapNotNull { it.date }
        val allDates = recordDates + circadianDates + scheduleDates
        val lastDate = allDates.maxOrNull() ?: referenceDate
        val naturalFirstDate = allDates.minOrNull() ?: lastDate
        val paddedFirstDate = lastDate.minusDays((minimumRows - 1).toLong())
        val firstDate = minOf(naturalFirstDate, paddedFirstDate)
        val recordsByDate = records.toCalendarRecordCandidates()
        val circadianByDate = circadianDays.toCalendarCircadianCandidates()

        val rows = generateSequence(firstDate) { current ->
            if (current < lastDate) current.plusDays(1) else null
        }.map { date ->
            val offset = offsetByDate[date] ?: fallbackOffset
            val rowStart = date.atStartOfDay().toInstant(offset)
            val rowEnd = date.plusDays(1).atStartOfDay().toInstant(offset)
            val sleeps = recordsByDate[date].orEmpty().mapNotNull { record ->
                record.toBlock(rowStart, rowEnd)
            }
            val overlays = circadianByDate[date].orEmpty().flatMap { circadian ->
                circadian.toCalendarOverlays(
                    rowStart = rowStart,
                    rowEnd = rowEnd,
                    dayMidnight = circadian.date.atStartOfDay().toInstant(offset),
                    zoneOffset = offset,
                )
            }.withoutOverlappingCircadianOverlays()
            ActogramRow(
                date = date,
                label = date.format(DateFormatter),
                startTime = rowStart,
                sleeps = sleeps,
                overlays = overlays,
                schedules = scheduleEntries.toScheduleBlocks(rowStart, rowEnd, offset),
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
        scheduleEntries: List<ScheduleEntry>,
        rowHours: Double,
        minimumRows: Int,
        referenceDate: LocalDate,
    ): ActogramLayout {
        val fallbackOffset = records.firstNotNullOfOrNull { it.startZoneOffset } ?: ZoneOffset.UTC
        val firstInstant = records.minOfOrNull { it.startTime }
        val firstDate = firstInstant?.atOffset(fallbackOffset)?.toLocalDate()
            ?: circadianDays.minOfOrNull { it.date }
            ?: scheduleEntries.mapNotNull { it.date }.minOrNull()
            ?: referenceDate
        val origin = firstDate.atStartOfDay().toInstant(fallbackOffset)
        val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
        val lastRecordInstant = records.maxOfOrNull { it.endTime }
        val lastCircadianInstant = circadianDays.maxOfOrNull { day ->
            day.date.plusDays(1).atStartOfDay().toInstant(fallbackOffset)
        }
        val lastScheduleInstant = scheduleEntries.mapNotNull { entry ->
            entry.date?.let { date -> entry.occurrenceEnd(date, fallbackOffset) }
        }.maxOrNull()
        val naturalEnd = listOfNotNull(lastRecordInstant, lastCircadianInstant, lastScheduleInstant).maxOrNull()
            ?: origin.plusMillis(rowDurationMs)
        val naturalCount = ceil(
            Duration.between(origin, naturalEnd).toMillis().coerceAtLeast(1L).toDouble() / rowDurationMs,
        ).toInt()
        val rowCount = maxOf(minimumRows, naturalCount)
        val circadianByDate = circadianDays.associateBy { it.date }
        val recordsByRow = records.toFixedDurationRecordCandidates(
            origin = origin,
            rowDurationMs = rowDurationMs,
            rowCount = rowCount,
        )

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
            val circadian = circadianByDate[date]
            val circadianMidnight = date.atStartOfDay().toInstant(fallbackOffset)

            ActogramRow(
                date = date,
                label = label,
                startTime = rowStart,
                sleeps = recordsByRow[index].mapNotNull { it.toBlock(rowStart, rowEnd) },
                overlays = circadian?.toFixedDurationOverlays(rowStart, rowEnd, circadianMidnight, fallbackOffset)
                    ?: emptyList(),
                schedules = scheduleEntries.toScheduleBlocks(rowStart, rowEnd, fallbackOffset),
            )
        }

        return ActogramLayout(
            rows = rows,
            rowHours = rowHours,
            hasRealData = records.isNotEmpty(),
            zoneOffset = fallbackOffset,
        )
    }

    private fun List<SleepRecord>.toCalendarRecordCandidates(): Map<LocalDate, List<SleepRecord>> {
        if (isEmpty()) return emptyMap()
        val candidates = mutableMapOf<LocalDate, MutableList<SleepRecord>>()
        forEach { record ->
            val startOffset = record.startZoneOffset ?: ZoneOffset.UTC
            val endOffset = record.endZoneOffset ?: startOffset
            val startDate = record.startTime.atOffset(startOffset).toLocalDate()
            val endDate = record.endTime.atOffset(endOffset).toLocalDate()
            val dates = buildSet {
                add(record.dateOfSleep)
                var current = startDate
                while (current <= endDate) {
                    add(current)
                    current = current.plusDays(1)
                }
            }
            dates.forEach { date ->
                candidates.getOrPut(date) { mutableListOf() }.add(record)
            }
        }
        return candidates
    }

    private fun List<SleepRecord>.toFixedDurationRecordCandidates(
        origin: Instant,
        rowDurationMs: Long,
        rowCount: Int,
    ): List<List<SleepRecord>> {
        if (rowCount <= 0) return emptyList()
        val buckets = List(rowCount) { mutableListOf<SleepRecord>() }
        if (isEmpty() || rowDurationMs <= 0L) return buckets

        forEach { record ->
            if (record.endTime <= record.startTime) return@forEach
            val firstIndex = floor(
                Duration.between(origin, record.startTime).toMillis().toDouble() / rowDurationMs,
            ).toInt()
            val lastIndex = floor(
                (Duration.between(origin, record.endTime).toMillis() - 1L).toDouble() / rowDurationMs,
            ).toInt()
            val first = max(0, firstIndex)
            val last = min(rowCount - 1, lastIndex)
            if (first <= last) {
                for (index in first..last) {
                    buckets[index].add(record)
                }
            }
        }
        return buckets
    }

    private fun List<CircadianDay>.toCalendarCircadianCandidates(): Map<LocalDate, List<CircadianDay>> {
        if (isEmpty()) return emptyMap()
        val candidates = mutableMapOf<LocalDate, MutableList<CircadianDay>>()
        forEach { day ->
            if (!day.isGap) {
                day.coveredCalendarDates().forEach { date ->
                    candidates.getOrPut(date) { mutableListOf() }.add(day)
                }
            }
        }
        return candidates
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

    private fun List<ActogramOverlayBlock>.withoutOverlappingCircadianOverlays(): List<ActogramOverlayBlock> {
        if (size < 2) return this

        val result = mutableListOf<ActogramOverlayBlock>()
        for (overlay in sortedWith(compareBy<ActogramOverlayBlock> { it.startHour }.thenBy { it.endHour })) {
            val previous = result.lastOrNull()
            if (previous == null || overlay.startHour >= previous.endHour) {
                result += overlay
                continue
            }

            if (overlay.endHour > previous.endHour) {
                result += overlay.copy(startHour = previous.endHour)
            }
        }
        return result
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

    private fun List<ScheduleEntry>.toScheduleBlocks(
        rowStart: Instant,
        rowEnd: Instant,
        zoneOffset: ZoneOffset,
    ): List<ActogramScheduleBlock> {
        if (isEmpty()) return emptyList()
        val firstDate = rowStart.atOffset(zoneOffset).toLocalDate().minusDays(1)
        val lastDate = rowEnd.atOffset(zoneOffset).toLocalDate().plusDays(1)
        val dates = generateSequence(firstDate) { current ->
            if (current < lastDate) current.plusDays(1) else null
        }.toList()

        return flatMap { entry ->
            if (!entry.enabled) {
                emptyList()
            } else {
                dates.mapNotNull { date -> entry.toScheduleBlock(date, rowStart, rowEnd, zoneOffset) }
            }
        }
    }

    private fun ScheduleEntry.toScheduleBlock(
        date: LocalDate,
        rowStart: Instant,
        rowEnd: Instant,
        zoneOffset: ZoneOffset,
    ): ActogramScheduleBlock? {
        if (daysOfWeek.isEmpty() && this.date != date) return null
        if (daysOfWeek.isNotEmpty() && date.dayOfWeek !in daysOfWeek) return null

        val start = occurrenceStart(date, zoneOffset)
        val end = occurrenceEnd(date, zoneOffset)
        val clippedStart = maxOf(start, rowStart)
        val clippedEnd = minOf(end, rowEnd)
        if (clippedEnd <= clippedStart) return null

        return ActogramScheduleBlock(
            entryId = id,
            startHour = hoursBetween(rowStart, clippedStart),
            endHour = hoursBetween(rowStart, clippedEnd),
            color = color,
            selection = ActogramSelection.Schedule(
                entry = this,
                occurrenceStart = start,
                occurrenceEnd = end,
                zoneOffset = zoneOffset,
            ),
        )
    }

    private fun ScheduleEntry.occurrenceStart(date: LocalDate, zoneOffset: ZoneOffset): Instant =
        LocalDateTime.of(date, startTime).toInstant(zoneOffset)

    private fun ScheduleEntry.occurrenceEnd(date: LocalDate, zoneOffset: ZoneOffset): Instant {
        val endDate = if (endTime <= startTime) date.plusDays(1) else date
        return LocalDateTime.of(endDate, endTime).toInstant(zoneOffset)
    }

}
