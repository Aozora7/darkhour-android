package one.aozora.darkhour.ui

import android.util.Log
import one.aozora.darkhour.BuildConfig
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.ui.actogram.ActogramLayout
import java.time.LocalDate

private data class CircadianDebugWindow(
    val rowDate: LocalDate,
    val sourceDate: LocalDate,
    val startHour: Double,
    val endHour: Double,
    val isForecast: Boolean,
    val confidence: Double,
)

internal fun logCircadianDebugDiagnostics(
    days: List<CircadianDay>,
    layout: ActogramLayout,
) {
    if (!BuildConfig.DEBUG || days.isEmpty()) return

    val duplicateDates = days
        .filterNot { it.isGap }
        .groupBy { it.date }
        .filterValues { it.size > 1 }
    if (duplicateDates.isNotEmpty()) {
        Log.w(
            CircadianDebugTag,
            "duplicate circadian source dates: " + duplicateDates.entries.joinToString { (date, entries) ->
                "$date=${entries.size}"
            },
        )
    }

    val overlaps = layout.rows.flatMap { row ->
        val windows = row.overlays.map { overlay ->
            CircadianDebugWindow(
                rowDate = row.date,
                sourceDate = overlay.selection.date,
                startHour = overlay.startHour,
                endHour = overlay.endHour,
                isForecast = overlay.isForecast,
                confidence = overlay.confidence,
            )
        }.sortedBy { it.startHour }

        windows.zipWithNext().filter { (previous, current) ->
            current.startHour < previous.endHour
        }
    }
    if (overlaps.isNotEmpty()) {
        Log.w(
            CircadianDebugTag,
            "overlapping circadian windows: " + overlaps.joinToString { (previous, current) ->
                "row ${previous.rowDate}: ${previous.sourceDate}${previous.forecastMarker()} " +
                    "${"%.2f".format(previous.startHour)}..${"%.2f".format(previous.endHour)} overlaps " +
                    "${current.sourceDate}${current.forecastMarker()} " +
                    "${"%.2f".format(current.startHour)}..${"%.2f".format(current.endHour)}"
            },
        )
    }
}

private fun CircadianDebugWindow.forecastMarker(): String =
    if (isForecast) "(forecast ${"%.2f".format(confidence)})" else "(observed ${"%.2f".format(confidence)})"

private const val CircadianDebugTag = "DarkHourCircadian"
