package one.aozora.darkhour.ui.actogram

data class ActogramGestureFrame(
    val rowHeightDp: Float,
    val rowHeightPx: Float,
    val scrollOffsetPx: Float,
    val maxScrollOffsetPx: Float,
    val anchorRow: Float,
    val focalY: Float,
    val focalContentRow: Float,
    val pointerCount: Int,
    val zoomDelta: Float,
    val panY: Float,
    val transforming: Boolean,
    val updatesRowHeight: Boolean,
    val reanchored: Boolean,
)

internal data class ActogramGestureUpdate(
    val frame: ActogramGestureFrame,
    val updatesScroll: Boolean,
)

internal class ActogramGestureTracker(
    initialScrollPx: Float,
    initialRowHeightDp: Float,
    private val density: Float,
    private val axisHeightPx: Float,
    private val viewportHeightPx: Float,
    private val minimumHeightPx: Float,
    private val realRowCount: Int,
) {
    private var internalScrollPx = initialScrollPx
    private var internalRowHeightDp = initialRowHeightDp
    private var gestureStartRowHeightDp = initialRowHeightDp
    private var gestureZoom = 1f
    private var anchoredRow = 0f
    private var currentFocalY = 0f
    private var previousPointerCount = 0

    fun sync(scrollPx: Float, rowHeightDp: Float) {
        internalScrollPx = scrollPx
        internalRowHeightDp = rowHeightDp
        gestureStartRowHeightDp = rowHeightDp
    }

    fun onTransformFrame(
        pointerCount: Int,
        focalY: Float,
        zoomDelta: Float,
        panY: Float,
    ): ActogramGestureUpdate {
        if (pointerCount != previousPointerCount || previousPointerCount == 0) {
            currentFocalY = focalY
            gestureStartRowHeightDp = internalRowHeightDp
            gestureZoom = 1f
            anchoredRow = calculateZoomAnchorRow(
                currentScroll = internalScrollPx,
                focalY = currentFocalY,
                axisHeight = axisHeightPx,
                rowHeight = gestureStartRowHeightDp * density,
            )
            previousPointerCount = pointerCount
            return ActogramGestureUpdate(
                frame = frame(
                    pointerCount = pointerCount,
                    zoomDelta = zoomDelta,
                    panY = panY,
                    updatesRowHeight = false,
                    reanchored = true,
                ),
                updatesScroll = false,
            )
        }

        currentFocalY += panY
        gestureZoom *= zoomDelta
        val newRowHeightDp = (gestureStartRowHeightDp * gestureZoom).coerceIn(
            ActogramGestureLimits.MinRowHeightDp,
            ActogramGestureLimits.MaxRowHeightDp,
        )
        val maxScrollOffsetPx = maxScrollForRowHeight(newRowHeightDp)
        val targetScrollPx = calculateZoomAnchoredScroll(
            anchoredRow = anchoredRow,
            focalY = currentFocalY,
            axisHeight = axisHeightPx,
            newRowHeight = newRowHeightDp * density,
        ).coerceIn(0f, maxScrollOffsetPx)

        val updatesRowHeight = newRowHeightDp != internalRowHeightDp
        internalRowHeightDp = newRowHeightDp
        internalScrollPx = targetScrollPx
        previousPointerCount = pointerCount

        return ActogramGestureUpdate(
            frame = frame(
                pointerCount = pointerCount,
                zoomDelta = zoomDelta,
                panY = panY,
                updatesRowHeight = updatesRowHeight,
                reanchored = false,
            ),
            updatesScroll = true,
        )
    }

    fun releasePointers() {
        previousPointerCount = 0
    }

    fun maxScrollForRowHeight(rowHeightDp: Float): Float =
        calculateActogramMaxScrollOffset(
            realRowCount = realRowCount,
            rowHeightPx = rowHeightDp * density,
            viewportHeightPx = viewportHeightPx,
            axisHeightPx = axisHeightPx,
            minimumHeightPx = minimumHeightPx,
        )

    private fun frame(
        pointerCount: Int,
        zoomDelta: Float,
        panY: Float,
        updatesRowHeight: Boolean,
        reanchored: Boolean,
    ): ActogramGestureFrame {
        val rowHeightPx = internalRowHeightDp * density
        val focalContentRow = if (rowHeightPx <= 0f) {
            0f
        } else {
            (internalScrollPx + currentFocalY - axisHeightPx) / rowHeightPx
        }
        return ActogramGestureFrame(
            rowHeightDp = internalRowHeightDp,
            rowHeightPx = rowHeightPx,
            scrollOffsetPx = internalScrollPx,
            maxScrollOffsetPx = maxScrollForRowHeight(internalRowHeightDp),
            anchorRow = anchoredRow,
            focalY = currentFocalY,
            focalContentRow = focalContentRow.coerceAtLeast(0f),
            pointerCount = pointerCount,
            zoomDelta = zoomDelta,
            panY = panY,
            transforming = true,
            updatesRowHeight = updatesRowHeight,
            reanchored = reanchored,
        )
    }
}

internal object ActogramGestureLimits {
    const val MinRowHeightDp = 12f
    const val MaxRowHeightDp = 60f
}
