package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import one.aozora.darkhour.core.circadian.csf.CsfAnalysis
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.PeriodogramResult
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.ui.actogram.ActogramLayout

@Immutable
data class SleepAnalysisState(
    val records: List<SleepRecord>,
    val analysis: CsfAnalysis,
    val periodogram: PeriodogramResult,
)

@Immutable
data class AppSettingsState(
    val settings: AppSettings,
    val onSettingsChange: (AppSettings) -> Unit,
)

@Immutable
data class ActogramDisplayState(
    val layout: ActogramLayout,
    val options: ActogramDisplayOptions,
    val onOptionsChange: (ActogramDisplayOptions) -> Unit,
)

@Immutable
data class ScheduleState(
    val entries: List<ScheduleEntry>,
    val pendingEditId: Long?,
    val onEntriesChange: (List<ScheduleEntry>) -> Unit,
    val onEditConsumed: () -> Unit,
    val onEditEntry: (Long) -> Unit,
)

@Immutable
data class HealthConnectState(
    val access: HealthConnectAccess,
    val dataRange: HealthDataRange,
    val hasHistoryPermission: Boolean,
    val isRefreshing: Boolean,
    val importedRecordCount: Int,
    val expectedRecordCount: Int?,
    val isImportPartial: Boolean,
    val importPhase: HealthImportPhase,
    val importError: String?,
    val totalHistoryDays: Int?,
    val onRequestHealthPermissions: () -> Unit,
    val onRequestHistoryPermission: () -> Unit,
    val onDataRangeChange: (HealthDataRange) -> Unit,
)

val LocalSleepAnalysis = staticCompositionLocalOf<SleepAnalysisState> {
    error("LocalSleepAnalysis was not provided")
}

val LocalAppSettings = staticCompositionLocalOf<AppSettingsState> {
    error("LocalAppSettings was not provided")
}

val LocalActogramDisplay = staticCompositionLocalOf<ActogramDisplayState> {
    error("LocalActogramDisplay was not provided")
}

val LocalScheduleState = staticCompositionLocalOf<ScheduleState> {
    error("LocalScheduleState was not provided")
}

val LocalHealthConnectState = staticCompositionLocalOf<HealthConnectState> {
    error("LocalHealthConnectState was not provided")
}

@Composable
fun DarkHourStateProvider(
    sleepAnalysis: SleepAnalysisState,
    appSettings: AppSettingsState,
    actogramDisplay: ActogramDisplayState,
    schedule: ScheduleState,
    healthConnect: HealthConnectState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSleepAnalysis provides sleepAnalysis,
        LocalAppSettings provides appSettings,
        LocalActogramDisplay provides actogramDisplay,
        LocalScheduleState provides schedule,
        LocalHealthConnectState provides healthConnect,
        content = content,
    )
}
