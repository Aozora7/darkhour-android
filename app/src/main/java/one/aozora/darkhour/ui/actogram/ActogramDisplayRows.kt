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
        val hiddenDoublePlotNextRow: ActogramRow? = null,
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
    val visibleRows = rows.dropLast(hiddenChronologicalTailRows.coerceIn(0, rows.size))
    val legendRows = if (includeLegend && hasRealData && rows.isNotEmpty()) {
        legendRows(scheduleLegendItems())
    } else {
        emptyList()
    }
    val minimumDataRows = (minimumRows - legendRows.size).coerceAtLeast(visibleRows.size)
    val orderedRows = visibleRows.toDisplayRows(
        order = order,
        hiddenChronologicalTailRow = rows.getOrNull(visibleRows.size),
    )
    if (visibleRows.isEmpty() || minimumDataRows <= visibleRows.size) {
        return when (order) {
            ActogramOrder.NEWEST_FIRST -> orderedRows + legendRows
            ActogramOrder.OLDEST_FIRST -> legendRows + orderedRows
        }
    }

    val missingRows = minimumDataRows - visibleRows.size
    val rowDurationMs = (rowHours * 3_600_000.0).roundToLong()
    val hasHiddenTail = hiddenChronologicalTailRows > 0
    val fillerRows = when (order) {
        ActogramOrder.NEWEST_FIRST -> {
            val oldest = visibleRows.first()
            (1..missingRows).map { distance ->
                emptyDataRow(oldest.startTime.minusMillis(distance * rowDurationMs))
            }
        }

        ActogramOrder.OLDEST_FIRST -> {
            if (hasHiddenTail) {
                val oldest = visibleRows.first()
                (missingRows downTo 1).map { distance ->
                    emptyDataRow(oldest.startTime.minusMillis(distance * rowDurationMs))
                }
            } else {
                val newest = visibleRows.last()
                (1..missingRows).map { distance ->
                    emptyDataRow(newest.startTime.plusMillis(distance * rowDurationMs))
                }
            }
        }
    }

    return when (order) {
        ActogramOrder.NEWEST_FIRST -> orderedRows + fillerRows.toDisplayRows() + legendRows
        ActogramOrder.OLDEST_FIRST -> if (hasHiddenTail) {
            legendRows + fillerRows.toDisplayRows() + orderedRows
        } else {
            legendRows + orderedRows + fillerRows.toDisplayRows()
        }
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

internal fun ActogramDisplayRow?.doublePlotNextDataRowOrNull(
    rows: List<ActogramDisplayRow>,
    index: Int,
    order: ActogramOrder,
): ActogramRow? {
    val dataRow = this as? ActogramDisplayRow.Data ?: return null
    return dataRow.hiddenDoublePlotNextRow
        ?: rows.getOrNull(nextChronologicalRowIndex(index, order)).dataRowOrNull()
}

private fun List<ActogramRow>.toDisplayRows(
    order: ActogramOrder = ActogramOrder.OLDEST_FIRST,
    hiddenChronologicalTailRow: ActogramRow? = null,
): List<ActogramDisplayRow.Data> {
    val rows = if (order == ActogramOrder.NEWEST_FIRST) asReversed() else this
    return rows.mapIndexed { index, row ->
        val isNewestVisibleRow = when (order) {
            ActogramOrder.NEWEST_FIRST -> index == 0
            ActogramOrder.OLDEST_FIRST -> index == rows.lastIndex
        }
        ActogramDisplayRow.Data(
            row = row,
            hiddenDoublePlotNextRow = hiddenChronologicalTailRow.takeIf { isNewestVisibleRow },
        )
    }
}

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
        zoneOffset = zoneOffset,
        sleeps = emptyList(),
        overlays = emptyList(),
        schedules = emptyList(),
    )
}
