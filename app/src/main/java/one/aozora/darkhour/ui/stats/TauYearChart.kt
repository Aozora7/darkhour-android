package one.aozora.darkhour.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.theme.ChartSelection
import one.aozora.darkhour.ui.theme.ChartTeal
import one.aozora.darkhour.ui.theme.CircadianForecast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun TauYearChart(
    series: List<YearlyTauSeries>,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val referenceColor = labelColor.copy(alpha = 0.35f)
    val seasonBandColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nonEmptySeries = series.filter { it.points.isNotEmpty() }
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
            val minYear = nonEmptySeries.minOf { it.year }
            val maxYear = nonEmptySeries.maxOf { it.year }

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

            drawAxes(yMin, yMax, left, top, bottom, labelColor)
            drawLegend(nonEmptySeries, minYear, maxYear, left, top, labelColor)
        }
    }
}

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLegend(
    series: List<YearlyTauSeries>,
    minYear: Int,
    maxYear: Int,
    left: Float,
    top: Float,
    labelColor: Color,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.toArgb()
        textSize = 10.dp.toPx()
        textAlign = Paint.Align.LEFT
    }
    var x = left
    var y = top - 10.dp.toPx()
    series.take(6).forEach { yearly ->
        val label = yearly.year.toString()
        val labelWidth = paint.measureText(label) + 26.dp.toPx()
        if (x + labelWidth > size.width - 12.dp.toPx()) {
            x = left
            y += 14.dp.toPx()
        }
        val color = yearGradientColor(yearly.year, minYear, maxYear)
        drawLine(
            color = color,
            start = Offset(x, y - 3.dp.toPx()),
            end = Offset(x + 14.dp.toPx(), y - 3.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawContext.canvas.nativeCanvas.drawText(label, x + 18.dp.toPx(), y, paint)
        x += labelWidth
    }
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
