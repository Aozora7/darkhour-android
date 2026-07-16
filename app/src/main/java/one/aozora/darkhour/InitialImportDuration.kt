package one.aozora.darkhour

import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import java.time.Duration
import kotlin.math.ceil

internal fun initialVisibleImportDuration(
    viewportHeightDp: Float,
    options: ActogramDisplayOptions,
): Duration {
    val visibleRows = ceil(
        ((viewportHeightDp - ACTOGRAM_AXIS_HEIGHT_DP).coerceAtLeast(0f) / options.rowHeightDp)
            .toDouble(),
    ).toLong()
    val doublePlotRows = if (options.doublePlot) 1L else 0L
    val rowHours = when (options.timeScale) {
        ActogramTimeScale.HOURS_24 -> 24.0
        ActogramTimeScale.CIRCADIAN_TAU -> 24.0
        ActogramTimeScale.CUSTOM -> options.customHours.toDouble()
    }
    val days = ceil(((visibleRows + doublePlotRows).coerceAtLeast(1L) * rowHours) / 24.0)
        .toLong()
        .coerceAtLeast(1L)
    return Duration.ofDays(days)
}

private const val ACTOGRAM_AXIS_HEIGHT_DP = 30f
