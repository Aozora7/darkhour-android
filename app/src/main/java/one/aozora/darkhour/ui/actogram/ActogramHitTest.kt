package one.aozora.darkhour.ui.actogram

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.floor

internal fun hitTestActogram(
    rows: List<ActogramDisplayRow>,
    options: ActogramDisplayOptions,
    rowHours: Double,
    canvasWidth: Float,
    position: Offset,
    density: Float,
    labelWidthPx: Float? = null,
    verticalScrollOffsetPx: Float = 0f,
): ActogramSelection? {
    val axisHeight = 30f * density
    val rowHeight = options.rowHeightDp * density
    val contentY = position.y + verticalScrollOffsetPx
    if (contentY < axisHeight || rowHeight <= 0f) return null

    val tauMode = abs(rowHours - 24.0) >= 0.0001
    val labelWidth = labelWidthPx ?: (actogramMaxLabelWidthDp(
        showDateLabels = options.showDateLabels,
        tauMode = tauMode,
        useIsoDateTime = false,
    ) * density)
    val rightPadding = 10f * density
    val plotWidth = (canvasWidth - labelWidth - rightPadding).coerceAtLeast(1f)
    if (position.x < labelWidth || position.x > labelWidth + plotWidth) return null

    val rowIndex = floor((contentY - axisHeight) / rowHeight).toInt()
    if (rowIndex !in rows.indices) return null
    if (rows[rowIndex] !is ActogramDisplayRow.Data) return null

    val displayedHours = rowHours * if (options.doublePlot) 2.0 else 1.0
    val plottedHour = ((position.x - labelWidth) / plotWidth) * displayedHours
    val sourceIndex: Int
    val sourceHour: Double
    if (options.doublePlot && plottedHour >= rowHours) {
        sourceIndex = nextChronologicalRowIndex(rowIndex, options.order)
        sourceHour = plottedHour - rowHours
    } else {
        sourceIndex = rowIndex
        sourceHour = plottedHour
    }
    val sourceRow = rows.getOrNull(sourceIndex).dataRowOrNull()
    val blockHeight = (rowHeight * 0.62f).coerceAtLeast(5f * density)
    val rowTop = axisHeight + rowIndex * rowHeight
    val blockTop = rowTop + (rowHeight - blockHeight) / 2f
    val withinSleepHeight = contentY in blockTop..(blockTop + blockHeight)

    if (withinSleepHeight) {
        sourceRow?.sleeps
            ?.lastOrNull { sourceHour in it.startHour..it.endHour }
            ?.let { return it.selection }
    }
    sourceRow?.overlays
        ?.lastOrNull { sourceHour in it.startHour..it.endHour }
        ?.let { return it.selection }
    if (options.showSchedule) {
        sourceRow?.schedules
            ?.lastOrNull { sourceHour in it.startHour..it.endHour }
            ?.let { return it.selection }
    }
    return null
}
