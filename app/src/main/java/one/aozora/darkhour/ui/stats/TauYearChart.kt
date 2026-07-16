package one.aozora.darkhour.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.theme.ChartSelection
import one.aozora.darkhour.ui.theme.ChartTeal
import one.aozora.darkhour.ui.theme.CircadianForecast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun TauYearChart(
    series: List<YearlyTauSeries>,
    colorYearRange: IntRange,
    modifier: Modifier = Modifier,
) {
    val nonEmptySeries = series.filter { it.points.isNotEmpty() }.sortedByDescending { it.year }
    var selectedDayOfYear by remember(series) { mutableStateOf<Int?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val referenceColor = labelColor.copy(alpha = 0.35f)
    val seasonBandColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBackgroundColor = Color(0xFF202124)
    val tooltipTextColor = Color(0xFFF1F3F4)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nonEmptySeries) {
                    observeChartPresses(
                        onPosition = { positionX ->
                            selectedDayOfYear = tauDayAtX(
                                positionX = positionX,
                                plotLeft = 48.dp.toPx(),
                                plotRight = size.width - 18.dp.toPx(),
                            )
                        },
                        onGestureCancelled = { selectedDayOfYear = null },
                    )
                },
        ) {
            if (nonEmptySeries.isEmpty()) {
                drawCenteredText("Not enough data", labelColor)
                return@Canvas
            }

            val left = 48.dp.toPx()
            val right = size.width - 18.dp.toPx()
            val top = 28.dp.toPx()
            val bottom = size.height - 32.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas

            val allTau = nonEmptySeries.flatMap { year -> year.points.map { it.tauHours } }
            val axisRange = tauAxisRange(allTau)
            val yMin = axisRange.min
            val yMax = axisRange.max
            val minYear = colorYearRange.first
            val maxYear = colorYearRange.last

            fun x(dayOfYear: Int): Float =
                left + ((dayOfYear - 1).coerceIn(0, 365) / 365f) * (right - left)

            fun y(tauHours: Double): Float =
                bottom - (((tauHours - yMin) / (yMax - yMin).coerceAtLeast(0.001)) * (bottom - top)).toFloat()

            astronomicalSeasonBands().forEachIndexed { index, band ->
                if (index % 2 == 0) {
                    val bandLeft = x(band.startDay)
                    val bandRight = x(band.endDay)
                    drawRect(
                        color = seasonBandColor.copy(alpha = 0.22f),
                        topLeft = Offset(bandLeft, top),
                        size = Size((bandRight - bandLeft).coerceAtLeast(1f), bottom - top),
                    )
                }
            }

            for (step in 0..4) {
                val lineY = top + (bottom - top) * step / 4f
                drawLine(gridColor, Offset(left, lineY), Offset(right, lineY), 1f)
            }

            listOf(1 to "Jan", 92 to "Apr", 183 to "Jul", 275 to "Oct").forEach { (day, label) ->
                val lineX = x(day)
                drawLine(gridColor, Offset(lineX, top), Offset(lineX, bottom), 1f)
                drawBottomLabel(label, lineX, bottom, labelColor.copy(alpha = 0.75f))
            }

            if (24.0 in yMin..yMax) {
                val refY = y(24.0)
                drawLine(
                    color = referenceColor,
                    start = Offset(left, refY),
                    end = Offset(right, refY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                )
                drawLabelAtEnd("24 h", right, refY, referenceColor)
            }

            nonEmptySeries.forEach {  yearly ->
                val color = yearGradientColor(yearly.year, minYear, maxYear)
                val path = Path()
                yearly.points.forEachIndexed { pointIndex, point ->
                    val px = x(point.dayOfYear)
                    val py = y(point.tauHours)
                    if (pointIndex == 0) {
                        path.moveTo(px, py)
                    } else {
                        path.lineTo(px, py)
                    }
                }
                if (yearly.points.size == 1) {
                    val only = yearly.points.first()
                    drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x(only.dayOfYear), y(only.tauHours)))
                } else {
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            selectedDayOfYear?.let { dayOfYear ->
                val tooltipValues = tauTooltipValues(nonEmptySeries, dayOfYear)
                if (tooltipValues.isNotEmpty()) {
                    drawTauValueTooltip(
                        dayOfYear = dayOfYear,
                        values = tooltipValues,
                        cursorX = x(dayOfYear),
                        valueY = { tauHours -> y(tauHours) },
                        minYear = minYear,
                        maxYear = maxYear,
                        left = left,
                        right = right,
                        top = top,
                        bottom = bottom,
                        backgroundColor = tooltipBackgroundColor,
                        textColor = tooltipTextColor,
                    )
                }
            }

            drawAxes(yMin, yMax, left, top, bottom, labelColor)
        }
    }
}

internal fun tauDayAtX(
    positionX: Float,
    plotLeft: Float,
    plotRight: Float,
): Int? {
    if (plotRight <= plotLeft || positionX !in plotLeft..plotRight) return null
    return (1 + ((positionX - plotLeft) / (plotRight - plotLeft) * 365f).roundToInt())
        .coerceIn(1, 366)
}

internal data class TauTooltipValue(
    val year: Int,
    val point: YearlyTauPoint,
)

internal fun tauTooltipValues(
    series: List<YearlyTauSeries>,
    dayOfYear: Int,
): List<TauTooltipValue> =
    series
        .sortedByDescending { it.year }
        .mapNotNull { yearly ->
            interpolatedTauPoint(yearly.points, dayOfYear)
                ?.let { point -> TauTooltipValue(yearly.year, point) }
        }

private fun interpolatedTauPoint(
    points: List<YearlyTauPoint>,
    dayOfYear: Int,
): YearlyTauPoint? {
    val sorted = points.sortedBy { it.dayOfYear }
    if (sorted.isEmpty() || dayOfYear !in sorted.first().dayOfYear..sorted.last().dayOfYear) return null
    val upperIndex = sorted.indexOfFirst { it.dayOfYear >= dayOfYear }
    val upper = sorted[upperIndex]
    if (upper.dayOfYear == dayOfYear || upperIndex == 0) return upper
    val lower = sorted[upperIndex - 1]
    val fraction = (dayOfYear - lower.dayOfYear).toDouble() /
        (upper.dayOfYear - lower.dayOfYear).toDouble()
    return YearlyTauPoint(
        dayOfYear = dayOfYear,
        tauHours = lower.tauHours + (upper.tauHours - lower.tauHours) * fraction,
        confidence = lower.confidence + (upper.confidence - lower.confidence) * fraction,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTauValueTooltip(
    dayOfYear: Int,
    values: List<TauTooltipValue>,
    cursorX: Float,
    valueY: (Double) -> Float,
    minYear: Int,
    maxYear: Int,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    backgroundColor: Color,
    textColor: Color,
) {
    drawLine(
        color = textColor.copy(alpha = 0.35f),
        start = Offset(cursorX, top),
        end = Offset(cursorX, bottom),
        strokeWidth = 1.dp.toPx(),
    )
    values.forEach { value ->
        val color = yearGradientColor(value.year, minYear, maxYear)
        drawCircle(
            color = backgroundColor,
            radius = 4.dp.toPx(),
            center = Offset(cursorX, valueY(value.point.tauHours)),
        )
        drawCircle(
            color = color,
            radius = 2.5.dp.toPx(),
            center = Offset(cursorX, valueY(value.point.tauHours)),
        )
    }

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor.toArgb()
        textSize = 11.dp.toPx()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor.toArgb()
        textSize = 10.dp.toPx()
    }
    val title = formatTauDayOfYear(dayOfYear)
    val valueLabels = values.map { value -> "%.2f h".format(value.point.tauHours) }
    val yearWidth = values.maxOf { rowPaint.measureText(it.year.toString()) }
    val valueWidth = valueLabels.maxOf(rowPaint::measureText)
    val horizontalPadding = 10.dp.toPx()
    val markerWidth = 13.dp.toPx()
    val columnGap = 16.dp.toPx()
    val panelWidth = maxOf(
        titlePaint.measureText(title),
        markerWidth + yearWidth + columnGap + valueWidth,
    ) + horizontalPadding * 2
    val titleHeight = 21.dp.toPx()
    val rowHeight = 16.dp.toPx()
    val verticalPadding = 7.dp.toPx()
    val panelHeight = verticalPadding * 2 + titleHeight + rowHeight * values.size
    val cursorGap = 8.dp.toPx()
    val panelLeft = if (cursorX + cursorGap + panelWidth <= right) {
        cursorX + cursorGap
    } else {
        (cursorX - cursorGap - panelWidth).coerceAtLeast(left)
    }
    val panelTop = (top + 5.dp.toPx()).coerceIn(
        minimumValue = top,
        maximumValue = (bottom - panelHeight).coerceAtLeast(top),
    )

    drawRoundRect(
        color = backgroundColor.copy(alpha = 0.96f),
        topLeft = Offset(panelLeft, panelTop),
        size = Size(panelWidth, panelHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(
        title,
        panelLeft + horizontalPadding,
        panelTop + verticalPadding + 11.dp.toPx(),
        titlePaint,
    )
    values.forEachIndexed { index, value ->
        val baseline = panelTop + verticalPadding + titleHeight + rowHeight * index + 11.dp.toPx()
        val markerCenterX = panelLeft + horizontalPadding + 4.dp.toPx()
        val markerCenterY = baseline - 3.5.dp.toPx()
        drawCircle(
            color = yearGradientColor(value.year, minYear, maxYear),
            radius = 3.5.dp.toPx(),
            center = Offset(markerCenterX, markerCenterY),
        )
        rowPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(
            value.year.toString(),
            panelLeft + horizontalPadding + markerWidth,
            baseline,
            rowPaint,
        )
        rowPaint.textAlign = Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(
            valueLabels[index],
            panelLeft + panelWidth - horizontalPadding,
            baseline,
            rowPaint,
        )
    }
}

private val TAU_TOOLTIP_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

internal fun formatTauDayOfYear(dayOfYear: Int): String =
    LocalDate.ofYearDay(2000, dayOfYear.coerceIn(1, 366)).format(TAU_TOOLTIP_DATE_FORMATTER)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(
    yMin: Double,
    yMax: Double,
    left: Float,
    top: Float,
    bottom: Float,
    color: Color,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText("%.1f".format(yMax), left - 8.dp.toPx(), top + 4.dp.toPx(), paint)
    drawContext.canvas.nativeCanvas.drawText("%.1f".format(yMin), left - 8.dp.toPx(), bottom, paint)

    paint.color = color.copy(alpha = 0.7f).toArgb()
    drawContext.canvas.nativeCanvas.save()
    drawContext.canvas.nativeCanvas.rotate(-90f, 12.dp.toPx(), (top + bottom) / 2)
    drawContext.canvas.nativeCanvas.drawText("Tau (hours)", 12.dp.toPx(), (top + bottom) / 2, paint)
    drawContext.canvas.nativeCanvas.restore()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBottomLabel(
    text: String,
    x: Float,
    bottom: Float,
    color: Color,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, bottom + 16.dp.toPx(), paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabelAtEnd(
    text: String,
    x: Float,
    y: Float,
    color: Color,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 9.dp.toPx()
        textAlign = Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y - 4.dp.toPx(), paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(text: String, color: Color) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 14.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}

internal data class AstronomicalSeasonBand(
    val startDay: Int,
    val endDay: Int,
)

internal data class TauAxisRange(
    val min: Double,
    val max: Double,
)

internal fun tauAxisRange(values: List<Double>): TauAxisRange {
    val rawMin = values.minOrNull() ?: 24.0
    val rawMax = values.maxOrNull() ?: 24.0
    val paddedMin = floor((rawMin - 0.08) * 10.0) / 10.0
    val paddedMax = ceil((rawMax + 0.08) * 10.0) / 10.0
    return if (paddedMax - paddedMin < 0.4) {
        TauAxisRange(
            min = paddedMin - 0.2,
            max = paddedMax + 0.2,
        )
    } else {
        TauAxisRange(
            min = paddedMin,
            max = paddedMax,
        )
    }
}

internal fun astronomicalSeasonBands(): List<AstronomicalSeasonBand> = listOf(
    AstronomicalSeasonBand(1, 79),
    AstronomicalSeasonBand(80, 172),
    AstronomicalSeasonBand(173, 265),
    AstronomicalSeasonBand(266, 355),
    AstronomicalSeasonBand(356, 366),
)

internal fun yearGradientColor(
    year: Int,
    minYear: Int,
    maxYear: Int,
): Color {
    val stops = listOf(
        ChartTeal,
        CircadianForecast,
        ChartSelection,
        Color(0xFFE57373),
        Color(0xFFBA68C8),
    )
    if (maxYear <= minYear) return stops.first()
    val t = ((year - minYear).toFloat() / (maxYear - minYear).toFloat()).coerceIn(0f, 1f)
    val scaled = t * (stops.lastIndex)
    val low = floor(scaled).roundToInt().coerceIn(0, stops.lastIndex)
    val high = ceil(scaled).roundToInt().coerceIn(0, stops.lastIndex)
    val localT = scaled - floor(scaled)
    return lerp(stops[low], stops[high], localT)
}
