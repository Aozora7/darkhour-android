package one.aozora.darkhour.ui.actogram

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun ActogramCanvas(
    modifier: Modifier = Modifier,
    layout: ActogramLayout,
    options: ActogramDisplayOptions,
    useIsoDateTime: Boolean,
    selection: ActogramSelection?,
    onSelectionChange: (ActogramSelection?) -> Unit,
    onRowHeightChange: (Float) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    onGestureFrame: ((ActogramGestureFrame) -> Unit)? = null,
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
        val legendRowCount = if (layout.hasRealData) 2 else 0
        val displayRowCount = max(layout.rows.size + legendRowCount, rowsNeededForViewport)
        val displayedRows = layout.rowsForDisplay(
            order = options.order,
            minimumRows = displayRowCount,
            includeLegend = true,
        )
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
                    row.label(
                        rowHours = layout.rowHours,
                        zoneOffset = layout.zoneOffset,
                        use24HourTime = use24HourTime,
                        useIsoDateTime = useIsoDateTime,
                    )
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
            axisHeightPx + rowHeightPx * displayedRows.size,
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
                        viewportHeightPx = viewportHeight,
                        minimumHeightPx = minimumHeightPx,
                        realRowCount = displayedRows.size,
                        currentRowHeight = { gestureRowHeightDp ?: currentRowHeight },
                        onGestureRowHeightChange = { gestureRowHeightDp = it },
                        onRowHeightChange = { currentRowHeightChange(it) },
                        onTransformingChange = { currentTransformingChange(it) },
                        onTransformingStateChange = {
                            isTransforming = it
                        },
                        onPendingAnchoredScrollChange = { pendingAnchoredScroll = it },
                        onGestureFrame = onGestureFrame,
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
    viewportHeightPx: Float,
    minimumHeightPx: Float,
    realRowCount: Int,
    currentRowHeight: () -> Float,
    onGestureRowHeightChange: (Float) -> Unit,
    onRowHeightChange: (Float) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    onTransformingStateChange: (Boolean) -> Unit,
    onPendingAnchoredScrollChange: (Float) -> Unit,
    onGestureFrame: ((ActogramGestureFrame) -> Unit)?,
) {
    awaitEachGesture {
        var gestureTransforming = false
        var readyForZoom = false
        var previousPointerCount = 0

        val tracker = ActogramGestureTracker(
            initialScrollPx = currentScroll(),
            initialRowHeightDp = currentRowHeight(),
            density = density,
            axisHeightPx = axisHeightPx,
            viewportHeightPx = viewportHeightPx,
            minimumHeightPx = minimumHeightPx,
            realRowCount = realRowCount,
        )

        try {
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }

                if (pressed >= 2) {
                    if (!gestureTransforming) {
                        gestureTransforming = true
                        onTransformingStateChange(true)
                        onTransformingChange(true)
                        // Sync with true state right as the gesture starts
                        tracker.sync(currentScroll(), currentRowHeight())
                    }

                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()

                    if (!readyForZoom || pressed != previousPointerCount) {
                        readyForZoom = true
                        val update = tracker.onTransformFrame(
                            pointerCount = pressed,
                            focalY = event.calculateCentroid().y,
                            zoomDelta = zoom,
                            panY = pan.y,
                        )
                        onGestureFrame?.invoke(update.frame)

                        event.changes.forEach { it.consume() }
                        previousPointerCount = pressed
                        continue
                    }

                    if (zoom != 1f || pan.y != 0f) {
                        val update = tracker.onTransformFrame(
                            pointerCount = pressed,
                            focalY = event.calculateCentroid().y,
                            zoomDelta = zoom,
                            panY = pan.y,
                        )
                        val frame = update.frame

                        onGestureFrame?.invoke(frame)
                        onPendingAnchoredScrollChange(frame.scrollOffsetPx)
                        onScrollTargetChange(frame.scrollOffsetPx)

                        if (frame.updatesRowHeight) {
                            onGestureRowHeightChange(frame.rowHeightDp)
                            onRowHeightChange(frame.rowHeightDp)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } else if (gestureTransforming) {
                    readyForZoom = false
                    tracker.releasePointers()
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
