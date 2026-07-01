package one.aozora.darkhour.ui.actogram

import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

data class ActogramLegendItem(
    val label: String,
    val color: Long,
)

enum class ActogramLegendKind {
    STAGES,
    OVERLAYS,
}

sealed interface ActogramDisplayRow {
    val date: LocalDate
    val startTime: Instant

    data class Data(
        val row: ActogramRow,
    ) : ActogramDisplayRow {
        override val date: LocalDate = row.date
        override val startTime: Instant = row.startTime
    }

    data class Legend(
        val kind: ActogramLegendKind,
        val label: String,
        val legendItems: List<ActogramLegendItem>,
        override val startTime: Instant,
        override val date: LocalDate,
    ) : ActogramDisplayRow
}

internal fun ActogramLayout.rowsForDisplay(
    order: ActogramOrder,
    minimumRows: Int,
    includeLegend: Boolean = false,
): List<ActogramDisplayRow> {
    val legendRows = if (includeLegend && hasRealData && rows.isNotEmpty()) {
        legendRows(scheduleLegendItems())
    } else {
        emptyList()
    }
    val minimumDataRows = (minimumRows - legendRows.size).coerceAtLeast(rows.size)
    val orderedRows = if (order == ActogramOrder.NEWEST_FIRST) rows.asReversed() else rows
    if (rows.isEmpty() || minimumDataRows <= rows.size) {
        return when (order) {
            ActogramOrder.NEWEST_FIRST -> orderedRows.toDisplayRows() + legendRows
            ActogramOrder.OLDEST_FIRST -> legendRows + orderedRows.toDisplayRows()
        }
    }

    val missingRows = minimumDataRows - rows.size
    val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
    val fillerRows = when (order) {
        ActogramOrder.NEWEST_FIRST -> {
            val oldest = rows.first()
            (1..missingRows).map { distance ->
                emptyDataRow(oldest.startTime.minusMillis(distance * rowDurationMs))
            }
        }

        ActogramOrder.OLDEST_FIRST -> {
            val newest = rows.last()
            (1..missingRows).map { distance ->
                emptyDataRow(newest.startTime.plusMillis(distance * rowDurationMs))
            }
        }
    }

    return when (order) {
        ActogramOrder.NEWEST_FIRST -> orderedRows.toDisplayRows() + fillerRows.toDisplayRows() + legendRows
        ActogramOrder.OLDEST_FIRST -> legendRows + orderedRows.toDisplayRows() + fillerRows.toDisplayRows()
    }
}

internal fun ActogramDisplayRow.label(
    rowHours: Double,
    zoneOffset: java.time.ZoneOffset,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean,
): String =
    when (this) {
        is ActogramDisplayRow.Legend -> label
        is ActogramDisplayRow.Data -> if (kotlin.math.abs(rowHours - 24.0) < 0.0001) {
            formatActogramDate(row.date, useIsoDateTime)
        } else {
            formatActogramRowLabel(row.startTime, zoneOffset, use24HourTime, useIsoDateTime)
        }
    }

private fun List<ActogramRow>.toDisplayRows(): List<ActogramDisplayRow.Data> =
    map { ActogramDisplayRow.Data(it) }

internal fun ActogramDisplayRow?.dataRowOrNull(): ActogramRow? =
    (this as? ActogramDisplayRow.Data)?.row

private fun ActogramLayout.legendRows(scheduleItems: List<ActogramLegendItem>): List<ActogramDisplayRow.Legend> {
    val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
    return listOf(
        legendRow(
            startTime = rows.first().startTime.minusMillis(rowDurationMs * 2),
            label = "Stages",
            kind = ActogramLegendKind.STAGES,
        ),
        legendRow(
            startTime = rows.first().startTime.minusMillis(rowDurationMs),
            label = "Overlays",
            kind = ActogramLegendKind.OVERLAYS,
            legendItems = scheduleItems,
        ),
    )
}

private fun ActogramLayout.legendRow(
    startTime: Instant,
    label: String,
    kind: ActogramLegendKind,
    legendItems: List<ActogramLegendItem> = emptyList(),
): ActogramDisplayRow.Legend =
    ActogramDisplayRow.Legend(
        kind = kind,
        label = label,
        legendItems = legendItems,
        startTime = startTime,
        date = startTime.atOffset(zoneOffset).toLocalDate(),
    )

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

private fun ActogramLayout.emptyDataRow(startTime: Instant): ActogramRow {
    val localStart = startTime.atOffset(zoneOffset)
    return ActogramRow(
        date = localStart.toLocalDate(),
        startTime = startTime,
        sleeps = emptyList(),
        overlays = emptyList(),
        schedules = emptyList(),
    )
}
