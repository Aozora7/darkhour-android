package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.ui.settings.AppSettings

internal data class DerivedSleepAnalysis(
    val state: SleepAnalysisState,
    val displayRecords: List<SleepRecord>,
    val actogramAnalysis: CircadianAnalysis,
    val hideActogramForecastTail: Boolean,
)

@Composable
internal fun rememberDerivedSleepAnalysis(
    displayRecords: List<SleepRecord>,
    analysisRecords: List<SleepRecord>,
    settings: AppSettings,
    developerState: DeveloperCircadianState,
): DerivedSleepAnalysis {
    val filteredDisplayRecords = remember(displayRecords, settings.includeNaps) {
        displayRecords.filteredByNapPreference(settings.includeNaps)
    }
    val filteredAnalysisRecords = remember(analysisRecords, settings.includeNaps) {
        analysisRecords.filteredByNapPreference(settings.includeNaps)
    }
    val activeOverrides = developerState.activeOverrides
    val analysis = remember(
        filteredAnalysisRecords,
        settings.forecastDays,
        developerState.algorithmId,
        activeOverrides,
    ) {
        CircadianAnalyzer.analyze(
            filteredAnalysisRecords,
            extraDays = settings.forecastDays,
            algorithmId = developerState.algorithmId,
            overrides = activeOverrides,
        )
    }
    val actogramForecastDays = actogramForecastDays(settings.forecastDays)
    val actogramAnalysis = remember(
        filteredAnalysisRecords,
        actogramForecastDays,
        developerState.algorithmId,
        activeOverrides,
    ) {
        CircadianAnalyzer.analyze(
            filteredAnalysisRecords,
            extraDays = actogramForecastDays,
            algorithmId = developerState.algorithmId,
            overrides = activeOverrides,
        )
    }
    val periodogram = remember(filteredAnalysisRecords) {
        computePeriodogram(buildPeriodogramAnchors(filteredAnalysisRecords))
    }

    return DerivedSleepAnalysis(
        state = SleepAnalysisState(
            records = filteredAnalysisRecords,
            analysis = analysis,
            periodogram = periodogram,
        ),
        displayRecords = filteredDisplayRecords,
        actogramAnalysis = actogramAnalysis,
        hideActogramForecastTail = actogramAnalysis.days.count { it.isForecast } >
            analysis.days.count { it.isForecast },
    )
}

internal fun List<SleepRecord>.filteredByNapPreference(
    includeNaps: Boolean,
): List<SleepRecord> = if (includeNaps) this else filter(SleepRecord::isMainSleep)

internal fun actogramForecastDays(forecastDays: Int): Int =
    if (forecastDays > 0) forecastDays + 1 else 0
