package one.aozora.darkhour.ui.settings

import androidx.compose.runtime.Immutable

@Immutable
data class AppSettings(
    val includeNaps: Boolean = true,
    val forecastDays: Int = 2,
    val useIsoDateTime: Boolean = false,
    val historyAccessCalloutDismissed: Boolean = false,
    val statsUseAllData: Boolean = false,
    val selectedTauYears: Set<Int>? = null,
)
