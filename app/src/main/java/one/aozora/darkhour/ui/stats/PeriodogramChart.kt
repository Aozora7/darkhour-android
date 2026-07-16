package one.aozora.darkhour.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.periodogram.PeriodogramPoint
import one.aozora.darkhour.core.periodogram.PeriodogramResult
import one.aozora.darkhour.ui.theme.ChartTeal
import one.aozora.darkhour.ui.theme.CircadianForecast
import kotlin.math.abs

@Composable
fun PeriodogramChart(
    result: PeriodogramResult,
    modifier: Modifier = Modifier,
) {
    val points = result.trimmedPoints.ifEmpty { result.points }
    var selectedPoint by remember(result) { mutableStateOf<PeriodogramPoint?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thresholdColor = Color(0xFFE57373) // Reddish for significance
    val peakColor = CircadianForecast

    Surface(
        modifier = modifier.testTag("periodogram_chart"),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    observeChartPresses(
                        onPosition = { positionX ->
                            selectedPoint = periodogramPointAtX(
                                points = points,
                                positionX = positionX,
                                plotLeft = 48.dp.toPx(),
                                plotRight = size.width - 20.dp.toPx(),
                            )
                        },
                        onGestureCancelled = { selectedPoint = null },
                    )
                },
        ) {
            if (points.isEmpty()) {
                drawCenteredText("Not enough data", labelColor)
                return@Canvas
            }

            val left = 48.dp.toPx()
            val right = size.width - 20.dp.toPx()
            val top = 32.dp.toPx() // More space for top labels
            val bottom = size.height - 36.dp.toPx() // More space for axis title
            val minPeriod = points.first().period
            val maxPeriod = points.last().period
            val maxPower = maxOf(points.maxOf { it.power }, result.significanceThreshold, 0.01)

            fun x(period: Double) =
                left + ((period - minPeriod) / (maxPeriod - minPeriod).coerceAtLeast(0.001) * (right - left)).toFloat()
            fun y(power: Double) =
                bottom - (power / maxPower * (bottom - top)).toFloat()

            // Grid lines
            for (step in 0..4) {
                val lineY = top + (bottom - top) * step / 4f
                drawLine(gridColor, Offset(left, lineY), Offset(right, lineY), 1f)
            }

            // 24h reference line
            if (24.0 in minPeriod..maxPeriod) {
                val refX = x(24.0)
                drawLine(
                    color = labelColor.copy(alpha = 0.25f),
                    start = Offset(refX, top),
                    end = Offset(refX, bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
                drawTopLabel("24h", refX, top, labelColor.copy(alpha = 0.6f))
            }

            // Peak period vertical line
            val peakX = x(result.peakPeriod)
            drawLine(
                color = peakColor.copy(alpha = 0.5f),
                start = Offset(peakX, top),
                end = Offset(peakX, bottom),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            )
            drawTopLabel("%.2fh".format(result.peakPeriod), peakX, top, peakColor)

            // Significance threshold
            val thresholdY = y(result.significanceThreshold)
            drawLine(
                color = thresholdColor.copy(alpha = 0.7f),
                start = Offset(left, thresholdY),
                end = Offset(right, thresholdY),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
            drawLabelAtEnd("p<0.01", right, thresholdY, thresholdColor.copy(alpha = 0.8f))

            // Main path and fill
            val path = Path()
            val fillPath = Path()
            points.forEachIndexed { index, point ->
                val px = x(point.period)
                val py = y(point.power)
                if (index == 0) {
                    path.moveTo(px, py)
                    fillPath.moveTo(px, bottom)
                    fillPath.lineTo(px, py)
                } else {
                    path.lineTo(px, py)
                    fillPath.lineTo(px, py)
                }
                if (index == points.lastIndex) {
                    fillPath.lineTo(px, bottom)
                    fillPath.close()
                }
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(ChartTeal.copy(alpha = 0.25f), Color.Transparent),
                    startY = top,
                    endY = bottom
                )
            )

            drawPath(
                path = path,
                color = ChartTeal,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // Peak dot
            drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(peakX, y(result.peakPower)))
            drawCircle(ChartTeal, radius = 2.dp.toPx(), center = Offset(peakX, y(result.peakPower)))

            selectedPoint?.let { selected ->
                drawValueIndicator(
                    point = selected,
                    pointX = x(selected.period),
                    pointY = y(selected.power),
                    left = left,
                    right = right,
                    top = top,
                    bottom = bottom,
                    labelColor = labelColor,
                    backgroundColor = backgroundColor,
                )
            }
            
            drawAxesDetails(minPeriod, maxPeriod, maxPower, left, right, top, bottom, labelColor)
        }
    }
}

internal suspend fun PointerInputScope.observeChartPresses(
    onPosition: (Float) -> Unit,
    onGestureCancelled: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val pointerId = down.id
        var horizontalSwipe = false
        var slowScrub = false
        var verticalGesture = false
        var lastPosition = start
        onPosition(start.x)

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            lastPosition = change.position
            val displacement = change.position - start
            if (!horizontalSwipe && !slowScrub && !verticalGesture) {
                when {
                    abs(displacement.y) > viewConfiguration.touchSlop &&
                        abs(displacement.y) > abs(displacement.x) -> {
                        verticalGesture = true
                        onGestureCancelled()
                    }
                    abs(displacement.x) > viewConfiguration.touchSlop -> {
                        val speedDpPerSecond = horizontalSpeedDpPerSecond(
                            displacementPx = displacement.x,
                            elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                            density = density,
                        )
                        if (speedDpPerSecond >= QUICK_SWIPE_SPEED_DP_PER_SECOND) {
                            horizontalSwipe = true
                            onGestureCancelled()
                        } else {
                            slowScrub = true
                        }
                    }
                }
            }
            if (slowScrub) {
                change.consume()
            }
            if (!horizontalSwipe && !verticalGesture) {
                onPosition(change.position.x)
            }
            if (!change.pressed) break
        }

        if (!horizontalSwipe && !verticalGesture) onPosition(lastPosition.x)
    }
}

internal fun horizontalSpeedDpPerSecond(
    displacementPx: Float,
    elapsedMillis: Long,
    density: Float,
): Float {
    if (elapsedMillis <= 0L || density <= 0f) return Float.POSITIVE_INFINITY
    return abs(displacementPx) / density / elapsedMillis * 1_000f
}

internal const val QUICK_SWIPE_SPEED_DP_PER_SECOND = 250f

internal fun periodogramPointAtX(
    points: List<PeriodogramPoint>,
    positionX: Float,
    plotLeft: Float,
    plotRight: Float,
): PeriodogramPoint? {
    if (points.isEmpty() || plotRight <= plotLeft || positionX !in plotLeft..plotRight) return null
    val minPeriod = points.first().period
    val maxPeriod = points.last().period
    val period = minPeriod +
        (positionX - plotLeft) / (plotRight - plotLeft) * (maxPeriod - minPeriod)
    return points.minByOrNull { abs(it.period - period) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawValueIndicator(
    point: PeriodogramPoint,
    pointX: Float,
    pointY: Float,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    labelColor: Color,
    backgroundColor: Color,
) {
    drawLine(
        color = ChartTeal.copy(alpha = 0.55f),
        start = Offset(pointX, top),
        end = Offset(pointX, bottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(backgroundColor, radius = 5.dp.toPx(), center = Offset(pointX, pointY))
    drawCircle(ChartTeal, radius = 3.dp.toPx(), center = Offset(pointX, pointY))

    val text = "%.2f h  Power %.3f".format(point.period, point.power)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.toArgb()
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    val horizontalPadding = 7.dp.toPx()
    val boxWidth = paint.measureText(text) + horizontalPadding * 2
    val boxHeight = 22.dp.toPx()
    val centerX = pointX.coerceIn(left + boxWidth / 2, right - boxWidth / 2)
    val boxTop = top + 4.dp.toPx()
    drawRoundRect(
        color = backgroundColor.copy(alpha = 0.94f),
        topLeft = Offset(centerX - boxWidth / 2, boxTop),
        size = Size(boxWidth, boxHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(
        text,
        centerX,
        boxTop + 15.dp.toPx(),
        paint,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopLabel(
    text: String,
    x: Float,
    y: Float,
    color: Color
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y - 8.dp.toPx(), paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabelAtEnd(
    text: String,
    x: Float,
    y: Float,
    color: Color
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 9.dp.toPx()
        textAlign = Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y - 4.dp.toPx(), paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxesDetails(
    minPeriod: Double,
    maxPeriod: Double,
    maxPower: Double,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 10.dp.toPx()
    }
    
    // Y-axis values
    paint.textAlign = Paint.Align.RIGHT
    drawContext.canvas.nativeCanvas.drawText("%.1f".format(maxPower), left - 8.dp.toPx(), top + 4.dp.toPx(), paint)
    drawContext.canvas.nativeCanvas.drawText("0.0", left - 8.dp.toPx(), bottom, paint)

    // X-axis values
    paint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText("%.0f".format(minPeriod), left, bottom + 16.dp.toPx(), paint)
    drawContext.canvas.nativeCanvas.drawText("%.0f".format(maxPeriod), right, bottom + 16.dp.toPx(), paint)

    // Axis titles
    paint.textSize = 10.dp.toPx()
    paint.color = color.copy(alpha = 0.7f).toArgb()
    
    // Y Axis Title
    drawContext.canvas.nativeCanvas.save()
    drawContext.canvas.nativeCanvas.rotate(-90f, 12.dp.toPx(), (top + bottom) / 2)
    drawContext.canvas.nativeCanvas.drawText("Power", 12.dp.toPx(), (top + bottom) / 2, paint)
    drawContext.canvas.nativeCanvas.restore()

    // X Axis Title
    drawContext.canvas.nativeCanvas.drawText("Period (hours)", (left + right) / 2, bottom + 30.dp.toPx(), paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(text: String, color: Color) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 14.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}
