package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.data.SleepRecordDisplayMetadata
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramLayout
import one.aozora.darkhour.ui.actogram.ActogramLayoutEngine
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import one.aozora.darkhour.ui.schedule.ScheduleEntry

@Composable
internal fun rememberActogramDisplayState(
    records: List<SleepRecord>,
    analysis: CircadianAnalysis,
    hideForecastTail: Boolean,
    options: ActogramDisplayOptions,
    scheduleEntries: List<ScheduleEntry>,
    recordMetadata: Map<Long, SleepRecordDisplayMetadata>,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
): ActogramDisplayState {
    val rowHours = actogramRowHours(options, analysis.globalTau)
    val baseLayout = remember(records, analysis.days, hideForecastTail, rowHours) {
        ActogramLayoutEngine.build(
            records = records,
            circadianDays = analysis.days,
            rowHours = rowHours,
        ).withHiddenActogramForecastTail(hideForecastTail)
    }
    val layout = remember(
        baseLayout,
        records,
        analysis.days,
        scheduleEntries,
        hideForecastTail,
        rowHours,
    ) {
        when {
            scheduleEntries.isEmpty() -> baseLayout
            scheduleEntries.any { it.date != null } -> ActogramLayoutEngine.build(
                records = records,
                circadianDays = analysis.days,
                scheduleEntries = scheduleEntries,
                rowHours = rowHours,
            ).withHiddenActogramForecastTail(hideForecastTail)
            else -> ActogramLayoutEngine.withScheduleEntries(baseLayout, scheduleEntries)
        }
    }

    LaunchedEffect(analysis.days, layout) {
        logCircadianDebugDiagnostics(analysis.days, layout)
    }

    return ActogramDisplayState(
        layout = layout,
        options = options,
        onOptionsChange = onOptionsChange,
        recordMetadata = recordMetadata,
    )
}

internal fun actogramRowHours(
    options: ActogramDisplayOptions,
    globalTau: Double,
): Double = when (options.timeScale) {
    ActogramTimeScale.HOURS_24 -> 24.0
    ActogramTimeScale.CIRCADIAN_TAU -> globalTau
    ActogramTimeScale.CUSTOM -> options.customHours.toDouble()
}

internal fun ActogramLayout.withHiddenActogramForecastTail(
    hideForecastTail: Boolean,
): ActogramLayout = if (hideForecastTail && rows.isNotEmpty()) {
    copy(hiddenChronologicalTailRows = 1)
} else {
    this
}
