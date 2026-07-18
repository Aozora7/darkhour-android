package one.aozora.darkhour.ui.settings

import androidx.compose.runtime.Immutable
import java.time.YearMonth

@Immutable
data class PeriodogramRangeSelection(
    /** Null keeps this endpoint attached to the current-month tick. */
    val newestMonth: YearMonth? = null,
    /** Null keeps this endpoint attached to the oldest-available tick. */
    val oldestMonth: YearMonth? = null,
)

@Immutable
data class AppSettings(
    val includeNaps: Boolean = true,
    val forecastDays: Int = 2,
    val useIsoDateTime: Boolean = false,
    val historyAccessCalloutDismissed: Boolean = false,
    val statsUseAllData: Boolean = false,
    val selectedTauYears: Set<Int>? = null,
    val periodogramRange: PeriodogramRangeSelection = PeriodogramRangeSelection(),
)
