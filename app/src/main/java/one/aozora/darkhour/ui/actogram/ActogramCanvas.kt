package one.aozora.darkhour.ui.actogram

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.ui.ActogramColorMode
import one.aozora.darkhour.ui.ActogramDisplayOptions
import one.aozora.darkhour.ui.ActogramOrder
import one.aozora.darkhour.ui.theme.ChartSelection
import one.aozora.darkhour.ui.theme.ChartSleepSolid
import one.aozora.darkhour.ui.theme.ChartTeal
import one.aozora.darkhour.ui.theme.CircadianForecast
import one.aozora.darkhour.ui.theme.CircadianObserved
import one.aozora.darkhour.ui.theme.SleepDeep
import one.aozora.darkhour.ui.theme.SleepLight
import one.aozora.darkhour.ui.theme.SleepRem
import one.aozora.darkhour.ui.theme.SleepWake
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@Composable
fun ActogramCanvas(
    layout: ActogramLayout,
    options: ActogramDisplayOptions,
    useIsoDateTime: Boolean,
    selection: ActogramSelection?,
    onSelectionChange: (ActogramSelection?) -> Unit,
    onRowHeightChange: (Float) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val use24HourTime = useIsoDateTime || android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val currentRowHeight by rememberUpdatedState(options.rowHeightDp)
    val currentRowHeightChange by rememberUpdatedState(onRowHeightChange)
    val currentTransformingChange by rememberUpdatedState(onTransformingChange)
    val axisHeight = 30.dp
    val minimumHeight = 240.dp
    var gestureRowHeightDp by remember { mutableStateOf<Float?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                stateDescription = "Row height ${"%.1f".format(options.rowHeightDp)}"
            },
    ) {
        val viewportHeight = with(density) { maxHeight.toPx() }
        val axisHeightPx = with(density) { axisHeight.toPx() }
        val displayedRowHeightDp = gestureRowHeightDp ?: options.rowHeightDp
        val rowHeightPx = with(density) { displayedRowHeightDp.dp.toPx() }
        val rowsNeededForViewport = ceil(
            ((viewportHeight - axisHeightPx).coerceAtLeast(0f) / rowHeightPx).toDouble(),
        ).toInt()
        val displayRowCount = max(layout.rows.size, rowsNeededForViewport)
        val displayedRows = layout.rowsForDisplay(options.order, displayRowCount)
        val tauMode = abs(layout.rowHours - 24.0) >= 0.0001
        val displayedLabels = remember(
            displayedRows,
            tauMode,
            use24HourTime,
            useIsoDateTime,
            options.showDateLabels,
        ) {
            if (!options.showDateLabels) {
                emptyList()
            } else {
                displayedRows.map { row ->
                    if (tauMode) {
                        formatActogramRowLabel(
                            row.startTime,
                            layout.zoneOffset,
                            use24HourTime,
                            useIsoDateTime,
                        )
                    } else {
                        formatActogramDate(row.date, useIsoDateTime)
                    }
                }
            }
        }
        val labelWidthPx = remember(
            displayedLabels,
            rowHeightPx,
            density.density,
            tauMode,
            useIsoDateTime,
            options.showDateLabels,
        ) {
            calculateActogramLabelWidthPx(
                labels = displayedLabels,
                rowHeightPx = rowHeightPx,
                density = density.density,
                showDateLabels = options.showDateLabels,
                tauMode = tauMode,
                useIsoDateTime = useIsoDateTime,
            )
        }
        val contentHeight = max(
            max(viewportHeight, with(density) { minimumHeight.toPx() }),
            axisHeightPx + rowHeightPx * displayRowCount,
        )
        val maxScrollOffset = (contentHeight - viewportHeight).coerceAtLeast(0f)
        val minimumHeightPx = with(density) { minimumHeight.toPx() }
        var scrollOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }
        var userScrolledFromInitialPosition by rememberSaveable(options.order) { mutableStateOf(false) }
        val currentScrollOffset by rememberUpdatedState(scrollOffsetPx)
        val currentMaxScrollOffset by rememberUpdatedState(maxScrollOffset)
        val scrollableState = rememberScrollableState { delta ->
            val oldOffset = scrollOffsetPx
            if (abs(delta) > 0.001f) {
                userScrolledFromInitialPosition = true
            }
            scrollOffsetPx = (scrollOffsetPx - delta).coerceIn(0f, currentMaxScrollOffset)
            oldOffset - scrollOffsetPx
        }
        var pendingAnchoredScroll by remember { mutableStateOf<Float?>(null) }
        var isTransforming by remember { mutableStateOf(false) }

        LaunchedEffect(gestureRowHeightDp, options.rowHeightDp, isTransforming) {
            val gestureHeight = gestureRowHeightDp ?: return@LaunchedEffect
            if (!isTransforming && abs(gestureHeight - options.rowHeightDp) < 0.001f) {
                gestureRowHeightDp = null
            }
        }

        LaunchedEffect(
            displayedRowHeightDp,
            contentHeight,
            isTransforming,
            options.order,
            userScrolledFromInitialPosition,
        ) {
            // Only snap if we aren't actively zooming/fighting the gesture
            if (pendingAnchoredScroll != null && !isTransforming) {
                scrollOffsetPx = pendingAnchoredScroll!!.coerceIn(0f, maxScrollOffset)
                pendingAnchoredScroll = null
            } else if (
                !isTransforming &&
                options.order == ActogramOrder.OLDEST_FIRST &&
                !userScrolledFromInitialPosition
            ) {
                scrollOffsetPx = maxScrollOffset
            } else if (!isTransforming && scrollOffsetPx > maxScrollOffset) {
                scrollOffsetPx = maxScrollOffset
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout, viewportHeight, axisHeightPx, minimumHeightPx) {
                    detectActogramTransformGestures(
                        currentScroll = { currentScrollOffset },
                        onScrollTargetChange = { target ->
                            userScrolledFromInitialPosition = true
                            scrollOffsetPx = target.coerceAtLeast(0f)
                        },
                        density = density.density,
                        axisHeightPx = axisHeightPx,
                        currentRowHeight = { gestureRowHeightDp ?: currentRowHeight },
                        maxScrollForRowHeight = { rowHeightDp ->
                            calculateActogramMaxScrollOffset(
                                realRowCount = layout.rows.size,
                                rowHeightPx = rowHeightDp * density.density,
                                viewportHeightPx = viewportHeight,
                                axisHeightPx = axisHeightPx,
                                minimumHeightPx = minimumHeightPx,
                            )
                        },
                        onGestureRowHeightChange = { gestureRowHeightDp = it },
                        onRowHeightChange = { currentRowHeightChange(it) },
                        onTransformingChange = { currentTransformingChange(it) },
                        onTransformingStateChange = {
                            isTransforming = it
                        },
                        onPendingAnchoredScrollChange = { pendingAnchoredScroll = it },
                    )
                }
                .scrollable(
                    state = scrollableState,
                    orientation = Orientation.Vertical,
                ),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(displayedRows, options, selection, labelWidthPx) {
                        detectTapGestures { position ->
                            onSelectionChange(
                                hitTestActogram(
                                    rows = displayedRows,
                                    options = options,
                                    rowHours = layout.rowHours,
                                    canvasWidth = size.width.toFloat(),
                                    position = position,
                                    density = density.density,
                                    labelWidthPx = labelWidthPx,
                                    verticalScrollOffsetPx = currentScrollOffset,
                                ),
                            )
                        }
                    },
            ) {
                val visibleRows = calculateVisibleRowWindow(
                    rowCount = displayedRows.size,
                    scrollOffsetPx = scrollOffsetPx,
                    viewportHeightPx = viewportHeight,
                    axisHeightPx = axisHeightPx,
                    rowHeightPx = rowHeightPx,
                )
                drawActogram(
                    layout,
                    displayedRows,
                    visibleRows,
                    scrollOffsetPx,
                    displayedRowHeightDp,
                    options,
                    selection,
                    use24HourTime,
                    useIsoDateTime,
                    labelWidthPx,
                )
            }
        }
    }
}

private suspend fun PointerInputScope.detectActogramTransformGestures(
    currentScroll: () -> Float,
    onScrollTargetChange: (Float) -> Unit,
    density: Float,
    axisHeightPx: Float,
    currentRowHeight: () -> Float,
    maxScrollForRowHeight: (Float) -> Float,
    onGestureRowHeightChange: (Float) -> Unit,
    onRowHeightChange: (Float) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    onTransformingStateChange: (Boolean) -> Unit,
    onPendingAnchoredScrollChange: (Float) -> Unit,
) {
    awaitEachGesture {
        var gestureTransforming = false
        var gestureStartRowHeight: Float? = null
        var gestureZoom = 1f
        var anchoredRow: Float? = null
        var readyForZoom = false
        var currentFocalY: Float? = null
        var previousPointerCount = 0 // NEW: Track pointer count

        try {
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }

                if (pressed >= 2) {
                    if (!gestureTransforming) {
                        gestureTransforming = true
                        onTransformingStateChange(true)
                        onTransformingChange(true)
                    }

                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()

                    if (!readyForZoom || pressed != previousPointerCount) {
                        readyForZoom = true
                        currentFocalY = event.calculateCentroid().y
                        gestureStartRowHeight = currentRowHeight()
                        gestureZoom = 1f
                        anchoredRow = calculateZoomAnchorRow(
                            currentScroll = currentScroll(),
                            focalY = currentFocalY!!,
                            axisHeight = axisHeightPx,
                            rowHeight = gestureStartRowHeight!! * density,
                        )

                        event.changes.forEach { it.consume() }
                        previousPointerCount = pressed // UPDATE: Sync pointer count
                        continue
                    }

                    // ... [zoom logic remains exactly the same here] ...
                    currentFocalY = currentFocalY!! + pan.y
                    if (zoom != 1f || pan.y != 0f) {
                        gestureZoom *= zoom
                        val newRowHeight = (gestureStartRowHeight!! * gestureZoom).coerceIn(12f, 60f)
                        val targetScroll = calculateZoomAnchoredScroll(
                            anchoredRow = anchoredRow!!,
                            focalY = currentFocalY!!,
                            axisHeight = axisHeightPx,
                            newRowHeight = newRowHeight * density,
                        ).coerceIn(0f, maxScrollForRowHeight(newRowHeight))

                        onPendingAnchoredScrollChange(targetScroll)
                        onScrollTargetChange(targetScroll)

                        if (newRowHeight != currentRowHeight()) {
                            onGestureRowHeightChange(newRowHeight)
                            onRowHeightChange(newRowHeight)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } else if (gestureTransforming) {
                    readyForZoom = false
                    event.changes.forEach { it.consume() }
                }

                previousPointerCount = pressed
            } while (event.changes.any { it.pressed })
        } finally {
            if (gestureTransforming) onTransformingChange(false)
            onTransformingStateChange(false)
        }
    }
}

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

private fun DrawScope.drawActogram(
    layout: ActogramLayout,
    displayedRows: List<ActogramRow>,
    visibleRows: IntRange,
    verticalScrollOffset: Float,
    rowHeightDp: Float,
    options: ActogramDisplayOptions,
    selection: ActogramSelection?,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean,
    labelWidth: Float,
) {
    val axisHeight = 30.dp.toPx()
    val rowHeight = rowHeightDp.dp.toPx()
    val tauMode = abs(layout.rowHours - 24.0) >= 0.0001
    val rightPadding = 10.dp.toPx()
    val plotWidth = (size.width - labelWidth - rightPadding).coerceAtLeast(1f)
    val displayedHours = layout.rowHours * if (options.doublePlot) 2.0 else 1.0
    val hourWidth = plotWidth / displayedHours.toFloat()
    val currentDate = LocalDate.now(layout.zoneOffset)
    drawRect(Color(0xFF101316))
    drawHourAxis(labelWidth, axisHeight, plotWidth, displayedHours, layout.rowHours, use24HourTime)

    clipRect(top = axisHeight) {
        visibleRows.forEach { index ->
            val row = displayedRows[index]
            val top = axisHeight + index * rowHeight - verticalScrollOffset
            val centerY = top + rowHeight / 2f
            val blockHeight = (rowHeight * 0.62f).coerceAtLeast(5.dp.toPx())

            drawRect(
                color = if (index % 2 == 0) Color(0xFF161B1F) else Color(0xFF12171A),
                topLeft = Offset(0f, top),
                size = Size(size.width, rowHeight),
            )
            dateColumnBackgroundColor(row.date, currentDate)?.let { color ->
                drawRect(
                    color = color,
                    topLeft = Offset(0f, top),
                    size = Size(labelWidth, rowHeight),
                )
            }
            drawLine(
                color = Color(0xFF2B3338),
                start = Offset(labelWidth, top + rowHeight),
                end = Offset(size.width - rightPadding, top + rowHeight),
                strokeWidth = 1f,
            )

            if (options.showDateLabels) {
                val label = if (tauMode) {
                    formatActogramRowLabel(
                        row.startTime,
                        layout.zoneOffset,
                        use24HourTime,
                        useIsoDateTime,
                    )
                } else {
                    formatActogramDate(row.date, useIsoDateTime)
                }
                drawDateLabel(label, labelWidth - 6.dp.toPx(), top, rowHeight, tauMode)
            }

            for (hour in 0..displayedHours.toInt() step 6) {
                val x = labelWidth + hour * hourWidth
                drawLine(
                    color = if (hour % 24 == 0) Color(0xFF66747C) else Color(0xFF30393E),
                    start = Offset(x, top),
                    end = Offset(x, top + rowHeight),
                    strokeWidth = if (hour % 24 == 0) 1.5f else 1f,
                )
            }

            if (options.showSchedule) {
                row.schedules.forEach { schedule ->
                    drawSchedule(schedule, 0.0, labelWidth, hourWidth, top, rowHeight, selection)
                }
                if (options.doublePlot) {
                    displayedRows.getOrNull(
                        nextChronologicalRowIndex(
                            index,
                            options.order
                        )
                    )?.schedules?.forEach { schedule ->
                        drawSchedule(
                            schedule,
                            layout.rowHours,
                            labelWidth,
                            hourWidth,
                            top,
                            rowHeight,
                            selection
                        )
                    }
                }
            }

            if (options.showCircadianOverlay) {
                row.overlays.forEach { overlay ->
                    drawOverlay(overlay, 0.0, labelWidth, hourWidth, top, rowHeight, selection)
                }
                if (options.doublePlot) {
                    displayedRows.getOrNull(
                        nextChronologicalRowIndex(
                            index,
                            options.order
                        )
                    )?.overlays?.forEach { overlay ->
                        drawOverlay(
                            overlay,
                            layout.rowHours,
                            labelWidth,
                            hourWidth,
                            top,
                            rowHeight,
                            selection,
                        )
                    }
                }
            }

            row.sleeps.forEach { sleep ->
                drawSleep(
                    sleep = sleep,
                    shift = 0.0,
                    left = labelWidth,
                    hourWidth = hourWidth,
                    top = centerY - blockHeight / 2f,
                    height = blockHeight,
                    colorMode = options.colorMode,
                    selection = selection,
                )
            }
            if (options.doublePlot) {
                displayedRows.getOrNull(
                    nextChronologicalRowIndex(
                        index,
                        options.order
                    )
                )?.sleeps?.forEach { sleep ->
                    drawSleep(
                        sleep = sleep,
                        shift = layout.rowHours,
                        left = labelWidth,
                        hourWidth = hourWidth,
                        top = centerY - blockHeight / 2f,
                        height = blockHeight,
                        colorMode = options.colorMode,
                        selection = selection,
                    )
                }
            }
        }
    }

    if (!layout.hasRealData) {
        drawEmptyMessage()
    }
}

private fun DrawScope.drawHourAxis(
    left: Float,
    axisHeight: Float,
    plotWidth: Float,
    displayedHours: Double,
    rowHours: Double,
    use24HourTime: Boolean,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(180, 192, 199)
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    val hourWidth = plotWidth / displayedHours.toFloat()
    for (hour in 0..displayedHours.toInt() step 6) {
        val label = if (abs(rowHours - 24.0) < 0.0001) {
            formatActogramAxisHour(hour, use24HourTime)
        } else {
            "+$hour"
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            left + hour * hourWidth,
            axisHeight - 9.dp.toPx(),
            paint,
        )
    }
}

private fun DrawScope.drawSchedule(
    schedule: ActogramScheduleBlock,
    shift: Double,
    left: Float,
    hourWidth: Float,
    rowTop: Float,
    rowHeight: Float,
    selection: ActogramSelection?,
) {
    val startX = left + ((schedule.startHour + shift) * hourWidth).toFloat()
    val endX = left + ((schedule.endHour + shift) * hourWidth).toFloat()
    if (endX <= left || startX >= size.width) return
    val selected = schedule.selection == selection
    val color = Color(schedule.color)
    drawRect(
        color = color.copy(alpha = if (selected) 0.36f else 0.22f),
        topLeft = Offset(startX, rowTop),
        size = Size((endX - startX).coerceAtLeast(1f), rowHeight),
    )
    if (selected) {
        drawRect(
            color = ChartSelection,
            topLeft = Offset(startX, rowTop + 1.dp.toPx()),
            size = Size(
                (endX - startX).coerceAtLeast(1f),
                (rowHeight - 2.dp.toPx()).coerceAtLeast(1f)
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun DrawScope.drawOverlay(
    overlay: ActogramOverlayBlock?,
    shift: Double,
    left: Float,
    hourWidth: Float,
    top: Float,
    height: Float,
    selection: ActogramSelection?,
) {
    if (overlay == null || overlay.isGap) return
    val startX = left + ((overlay.startHour + shift) * hourWidth).toFloat()
    val endX = left + ((overlay.endHour + shift) * hourWidth).toFloat()
    if (endX <= startX) return
    val baseAlpha = if (overlay.isForecast) 0.12f else 0.10f
    val confidenceAlpha = (overlay.confidence.coerceIn(0.0, 1.0) * 0.25).toFloat()
    val selected = overlay.selection == selection
    drawRect(
        color = if (overlay.isForecast) {
            CircadianForecast.copy(alpha = baseAlpha + confidenceAlpha + if (selected) 0.18f else 0f)
        } else {
            CircadianObserved.copy(alpha = baseAlpha + confidenceAlpha + if (selected) 0.18f else 0f)
        },
        topLeft = Offset(startX, top),
        size = Size((endX - startX).coerceAtLeast(1f), height),
    )
    if (selected) {
        drawRect(
            color = ChartSelection,
            topLeft = Offset(startX, top + 1.dp.toPx()),
            size = Size(
                (endX - startX).coerceAtLeast(1f),
                (height - 2.dp.toPx()).coerceAtLeast(1f)
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun DrawScope.drawDateLabel(
    label: String,
    right: Float,
    top: Float,
    rowHeight: Float,
    compact: Boolean,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(190, 200, 205)
        textSize = (if (compact) 8.dp else 10.dp).toPx().coerceAtMost(rowHeight * 0.5f)
        textAlign = Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText(
        label,
        right,
        top + rowHeight / 2f - (paint.ascent() + paint.descent()) / 2f,
        paint,
    )
}

private fun DrawScope.drawSleep(
    sleep: ActogramSleepBlock,
    shift: Double,
    left: Float,
    hourWidth: Float,
    top: Float,
    height: Float,
    colorMode: ActogramColorMode,
    selection: ActogramSelection?,
) {
    val startX = left + ((sleep.startHour + shift) * hourWidth).toFloat()
    val endX = left + ((sleep.endHour + shift) * hourWidth).toFloat()
    if (endX <= left || startX >= size.width) return

    val baseColor = when (colorMode) {
        ActogramColorMode.STAGES -> Color(0xFF7D8B92)
        ActogramColorMode.SLEEP_SCORE -> scoreColor(sleep.sleepScore)
        ActogramColorMode.SOLID -> if (sleep.isMainSleep) ChartSleepSolid else Color(0xFF9FA8AD)
    }
    drawRect(
        color = baseColor.copy(alpha = if (sleep.isMainSleep) 0.95f else 0.55f),
        topLeft = Offset(startX, top),
        size = Size((endX - startX).coerceAtLeast(1f), height),
    )

    if (colorMode == ActogramColorMode.STAGES && sleep.stages.isNotEmpty()) {
        sleep.stages.forEach { stage ->
            val stageStart = left + ((stage.startHour + shift) * hourWidth).toFloat()
            val stageEnd = left + ((stage.endHour + shift) * hourWidth).toFloat()
            drawRect(
                color = stageColor(stage.level),
                topLeft = Offset(stageStart, top),
                size = Size((stageEnd - stageStart).coerceAtLeast(1f), height),
            )
        }
    }

    val selected = sleep.selection == selection
    drawRect(
        color = if (selected) ChartSelection else Color.White.copy(alpha = 0.18f),
        topLeft = Offset(startX, top),
        size = Size((endX - startX).coerceAtLeast(1f), height),
        style = Stroke(width = (if (selected) 2f else 0.7f).dp.toPx()),
    )
}

internal fun hitTestActogram(
    rows: List<ActogramRow>,
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
    val sourceRow = rows.getOrNull(sourceIndex)
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

private fun nextChronologicalRowIndex(index: Int, order: ActogramOrder): Int =
    when (order) {
        ActogramOrder.NEWEST_FIRST -> index - 1
        ActogramOrder.OLDEST_FIRST -> index + 1
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

private fun calculateActogramLabelWidthPx(
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

private fun dateColumnBackgroundColor(date: LocalDate, currentDate: LocalDate): Color? =
    when {
        date == currentDate -> ChartSelection.copy(alpha = 0.24f)
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY ->
            ChartTeal.copy(alpha = 0.12f)
        else -> null
    }

private fun stageColor(level: SleepStageLevel): Color = when (level) {
    SleepStageLevel.WAKE -> SleepWake
    SleepStageLevel.LIGHT -> SleepLight
    SleepStageLevel.DEEP -> SleepDeep
    SleepStageLevel.REM -> SleepRem
}

private fun scoreColor(score: Double?): Color {
    val value = (score ?: 0.5).coerceIn(0.0, 1.0).toFloat()
    return Color(
        red = 1f - value * 0.87f,
        green = 0.33f + value * 0.51f,
        blue = 0.30f + value * 0.35f,
    )
}

private fun DrawScope.drawEmptyMessage() {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(190, 200, 205)
        textSize = 14.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        "No sleep records",
        size.width / 2f,
        size.height / 2f,
        paint,
    )
}
