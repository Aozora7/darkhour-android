package one.aozora.darkhour.ui.actogram

import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal fun calculateZoomAnchorRow(
    currentScroll: Float,
    focalY: Float,
    axisHeight: Float,
    rowHeight: Float,
): Float {
    if (rowHeight <= 0f) return 0f
    return ((currentScroll + focalY - axisHeight) / rowHeight).coerceAtLeast(0f)
}

internal fun calculateZoomAnchoredScroll(
    anchoredRow: Float,
    focalY: Float,
    axisHeight: Float,
    newRowHeight: Float,
): Float {
    if (newRowHeight <= 0f) return 0f
    return (axisHeight + anchoredRow.coerceAtLeast(0f) * newRowHeight - focalY).coerceAtLeast(0f)
}

internal fun calculateActogramMaxScrollOffset(
    realRowCount: Int,
    rowHeightPx: Float,
    viewportHeightPx: Float,
    axisHeightPx: Float,
    minimumHeightPx: Float,
): Float {
    if (rowHeightPx <= 0f) return 0f
    val rowsNeededForViewport = ceil(
        ((viewportHeightPx - axisHeightPx).coerceAtLeast(0f) / rowHeightPx).toDouble(),
    ).toInt()
    val displayRowCount = max(realRowCount, rowsNeededForViewport)
    val contentHeight = max(
        max(viewportHeightPx, minimumHeightPx),
        axisHeightPx + rowHeightPx * displayRowCount,
    )
    return (contentHeight - viewportHeightPx).coerceAtLeast(0f)
}

internal fun calculateVisibleRowWindow(
    rowCount: Int,
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
    axisHeightPx: Float,
    rowHeightPx: Float,
    overscanRows: Int = 2,
): IntRange {
    if (rowCount <= 0) return IntRange.EMPTY
    if (rowHeightPx <= 0f) return 0..0

    val visibleTop = scrollOffsetPx.coerceAtLeast(0f)
    val visibleBottom = (visibleTop + viewportHeightPx).coerceAtLeast(visibleTop)
    val firstVisible = floor((visibleTop - axisHeightPx) / rowHeightPx).toInt()
    val lastVisible = ceil((visibleBottom - axisHeightPx) / rowHeightPx).toInt()
    val first = (firstVisible - overscanRows).coerceIn(0, rowCount - 1)
    val last = (lastVisible + overscanRows).coerceIn(first, rowCount - 1)
    return first..last
}

internal fun calculateActogramLabelWidthPx(
    labels: List<String>,
    rowHeightPx: Float,
    density: Float,
    showDateLabels: Boolean,
    tauMode: Boolean,
    useIsoDateTime: Boolean,
): Float {
    if (!showDateLabels) return 10f * density

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = actogramLabelTextSizePx(rowHeightPx, density, tauMode)
    }
    val measuredWidth = labels.maxOfOrNull(paint::measureText) ?: 0f
    val minimumWidth = actogramMinLabelWidthDp(true, tauMode) * density
    val maximumWidth = actogramMaxLabelWidthDp(true, tauMode, useIsoDateTime) * density
    val paddedWidth = measuredWidth + 12f * density
    return ceil(paddedWidth.coerceIn(minimumWidth, maximumWidth) / density) * density
}

internal fun nextChronologicalRowIndex(index: Int, order: ActogramOrder): Int =
    when (order) {
        ActogramOrder.NEWEST_FIRST -> index - 1
        ActogramOrder.OLDEST_FIRST -> index + 1
    }
