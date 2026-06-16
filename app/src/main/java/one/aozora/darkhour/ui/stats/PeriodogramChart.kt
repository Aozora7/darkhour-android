package one.aozora.darkhour.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.periodogram.PeriodogramResult
import one.aozora.darkhour.ui.theme.ChartTeal
import one.aozora.darkhour.ui.theme.CircadianForecast

@Composable
fun PeriodogramChart(
    result: PeriodogramResult,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val points = result.trimmedPoints.ifEmpty { result.points }
        drawRect(Color(0xFF12171A))
        if (points.isEmpty()) {
            drawCenteredText("Not enough data")
            return@Canvas
        }

        val left = 42.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val minPeriod = points.first().period
        val maxPeriod = points.last().period
        val maxPower = maxOf(points.maxOf { it.power }, result.significanceThreshold, 0.01)

        fun x(period: Double) =
            left + ((period - minPeriod) / (maxPeriod - minPeriod).coerceAtLeast(0.001) * (right - left)).toFloat()
        fun y(power: Double) =
            bottom - (power / maxPower * (bottom - top)).toFloat()

        for (step in 0..4) {
            val lineY = top + (bottom - top) * step / 4f
            drawLine(Color(0xFF303A3F), Offset(left, lineY), Offset(right, lineY), 1f)
        }

        val thresholdY = y(result.significanceThreshold)
        drawLine(
            color = CircadianForecast,
            start = Offset(left, thresholdY),
            end = Offset(right, thresholdY),
            strokeWidth = 1.2.dp.toPx(),
        )

        if (24.0 in minPeriod..maxPeriod) {
            val referenceX = x(24.0)
            drawLine(
                color = Color(0xFF7E8B91),
                start = Offset(referenceX, top),
                end = Offset(referenceX, bottom),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val pointX = x(point.period)
            val pointY = y(point.power)
            if (index == 0) path.moveTo(pointX, pointY) else path.lineTo(pointX, pointY)
        }
        drawPath(
            path = path,
            color = ChartTeal,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )

        val peakX = x(result.peakPeriod)
        val peakY = y(result.peakPower)
        drawCircle(Color(0xFFF0F5F2), radius = 3.5.dp.toPx(), center = Offset(peakX, peakY))
        drawAxesLabels(minPeriod, maxPeriod, maxPower, left, right, top, bottom)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxesLabels(
    minPeriod: Double,
    maxPeriod: Double,
    maxPower: Double,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(184, 197, 202)
        textSize = 10.dp.toPx()
    }
    paint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText("%.1f".format(minPeriod), left, bottom + 18.dp.toPx(), paint)
    drawContext.canvas.nativeCanvas.drawText("%.1f h".format(maxPeriod), right, bottom + 18.dp.toPx(), paint)
    paint.textAlign = Paint.Align.RIGHT
    drawContext.canvas.nativeCanvas.drawText("%.2f".format(maxPower), left - 6.dp.toPx(), top + 4.dp.toPx(), paint)
    drawContext.canvas.nativeCanvas.drawText("0", left - 6.dp.toPx(), bottom, paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(text: String) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(184, 197, 202)
        textSize = 14.dp.toPx()
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}
