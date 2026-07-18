package one.aozora.darkhour.ui.stats

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.aozora.darkhour.ui.settings.PeriodogramRangeSelection
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeriodogramRangeControl(
    selection: PeriodogramRangeSelection,
    bounds: PeriodogramMonthBounds,
    useIsoDateTime: Boolean,
    onSelectionChange: (PeriodogramRangeSelection) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsets by remember(selection, bounds) {
        mutableStateOf(periodogramRangeOffsets(selection, bounds))
    }
    val startInteractionSource = remember { MutableInteractionSource() }
    val endInteractionSource = remember { MutableInteractionSource() }
    val startDragged by startInteractionSource.collectIsDraggedAsState()
    val endDragged by endInteractionSource.collectIsDraggedAsState()
    val dragging = startDragged || endDragged
    val yearBoundaryOffsets = remember(bounds) { periodogramYearBoundaryOffsets(bounds) }
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val yearTickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueLabelColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(dragging) {
        onDraggingChange(dragging)
    }
    DisposableEffect(onDraggingChange) {
        onDispose { onDraggingChange(false) }
    }

    if (bounds.lastMonthOffset > 0) {
        Box(modifier = modifier.testTag("periodogram_range_control")) {
            RangeSlider(
                value = offsets,
                onValueChange = { range ->
                    offsets = range.start.roundToInt().toFloat()..range.endInclusive.roundToInt().toFloat()
                },
                onValueChangeFinished = {
                    onSelectionChange(periodogramSelectionForOffsets(offsets, bounds))
                },
                valueRange = 0f..bounds.lastMonthOffset.toFloat(),
                startInteractionSource = startInteractionSource,
                endInteractionSource = endInteractionSource,
                track = { state ->
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                    ) {
                        val trackY = center.y
                        val valueSpan = (state.valueRange.endInclusive - state.valueRange.start)
                            .coerceAtLeast(1f)
                        fun valueX(value: Float): Float =
                            size.width * (value - state.valueRange.start) / valueSpan

                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(0f, trackY),
                            end = Offset(size.width, trackY),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = activeTrackColor,
                            start = Offset(valueX(state.activeRangeStart), trackY),
                            end = Offset(valueX(state.activeRangeEnd), trackY),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(yearTickColor, radius = 2.dp.toPx(), center = Offset(0f, trackY))
                        drawCircle(
                            yearTickColor,
                            radius = 2.dp.toPx(),
                            center = Offset(size.width, trackY),
                        )

                        val yearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = yearTickColor.toArgb()
                            textSize = 8.sp.toPx()
                            textAlign = Paint.Align.CENTER
                        }
                        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = valueLabelColor.toArgb()
                            textSize = 9.sp.toPx()
                        }
                        val valueBaseline = trackY - 7.dp.toPx()
                        val valueGap = 10.dp.toPx()
                        val leftThumbX = valueX(state.activeRangeStart)
                        val rightThumbX = valueX(state.activeRangeEnd)
                        val leftValue = formatPeriodogramMonth(
                            bounds.newest.minusMonths(state.activeRangeStart.roundToInt().toLong()),
                            useIsoDateTime,
                        )
                        val rightValue = formatPeriodogramMonth(
                            bounds.newest.minusMonths(state.activeRangeEnd.roundToInt().toLong()),
                            useIsoDateTime,
                        )
                        val leftValueWidth = valuePaint.measureText(leftValue)
                        val rightValueWidth = valuePaint.measureText(rightValue)
                        val leftValueX = (leftThumbX + valueGap)
                            .coerceAtMost((size.width - leftValueWidth).coerceAtLeast(0f))
                        val rightValueX = (rightThumbX - valueGap)
                            .coerceAtLeast(rightValueWidth.coerceAtMost(size.width))
                        val leftValueBounds = textBounds(
                            text = leftValue,
                            anchorX = leftValueX,
                            baseline = valueBaseline,
                            alignment = Paint.Align.LEFT,
                            paint = valuePaint,
                        )
                        var rightValueBaseline = valueBaseline
                        var rightValueBounds = textBounds(
                            text = rightValue,
                            anchorX = rightValueX,
                            baseline = rightValueBaseline,
                            alignment = Paint.Align.RIGHT,
                            paint = valuePaint,
                        )
                        if (RectF.intersects(leftValueBounds, rightValueBounds)) {
                            rightValueBaseline = trackY + 12.dp.toPx()
                            rightValueBounds = textBounds(
                                text = rightValue,
                                anchorX = rightValueX,
                                baseline = rightValueBaseline,
                                alignment = Paint.Align.RIGHT,
                                paint = valuePaint,
                            )
                        }
                        val thumbHalfWidth = 10.dp.toPx()
                        val thumbBounds = listOf(
                            RectF(leftThumbX - thumbHalfWidth, 0f, leftThumbX + thumbHalfWidth, size.height),
                            RectF(rightThumbX - thumbHalfWidth, 0f, rightThumbX + thumbHalfWidth, size.height),
                        )

                        yearBoundaryOffsets.forEach { monthOffset ->
                            val x = size.width * monthOffset / bounds.lastMonthOffset
                            drawLine(
                                color = yearTickColor,
                                start = Offset(x, trackY - 5.dp.toPx()),
                                end = Offset(x, trackY + 5.dp.toPx()),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                        yearBoundaryOffsets.forEach { monthOffset ->
                            val x = size.width * monthOffset / bounds.lastMonthOffset
                            val year = bounds.newest.minusMonths(monthOffset.toLong()).year.toString()
                            val alignment = when (monthOffset) {
                                0 -> Paint.Align.LEFT
                                bounds.lastMonthOffset -> Paint.Align.RIGHT
                                else -> Paint.Align.CENTER
                            }
                            val yearBounds = textBounds(
                                text = year,
                                anchorX = x,
                                baseline = valueBaseline,
                                alignment = alignment,
                                paint = yearPaint,
                            )
                            val overlapsThumb = thumbBounds.any { RectF.intersects(yearBounds, it) }
                            val overlapsValue = RectF.intersects(yearBounds, leftValueBounds) ||
                                RectF.intersects(yearBounds, rightValueBounds)
                            if (!overlapsThumb && !overlapsValue) {
                                yearPaint.textAlign = alignment
                                drawContext.canvas.nativeCanvas.drawText(
                                    year,
                                    x,
                                    valueBaseline,
                                    yearPaint,
                                )
                            }
                        }
                        valuePaint.textAlign = Paint.Align.LEFT
                        drawContext.canvas.nativeCanvas.drawText(
                            leftValue,
                            leftValueX,
                            valueBaseline,
                            valuePaint,
                        )
                        valuePaint.textAlign = Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(
                            rightValue,
                            rightValueX,
                            rightValueBaseline,
                            valuePaint,
                        )
                    }
                },
                steps = (bounds.lastMonthOffset - 1).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("periodogram_month_range"),
            )
        }
    }
}

private fun textBounds(
    text: String,
    anchorX: Float,
    baseline: Float,
    alignment: Paint.Align,
    paint: Paint,
): RectF {
    val width = paint.measureText(text)
    val left = when (alignment) {
        Paint.Align.LEFT -> anchorX
        Paint.Align.CENTER -> anchorX - width / 2f
        Paint.Align.RIGHT -> anchorX - width
    }
    return RectF(
        left,
        baseline + paint.fontMetrics.ascent,
        left + width,
        baseline + paint.fontMetrics.descent,
    )
}

private fun formatPeriodogramMonth(month: YearMonth, useIsoDateTime: Boolean): String =
    month.format(if (useIsoDateTime) ISO_MONTH_FORMAT else LOCALIZED_MONTH_FORMAT)

private val ISO_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")
private val LOCALIZED_MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy")
