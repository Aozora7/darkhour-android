package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import one.aozora.darkhour.core.circadian.CircadianAnalysis
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.PeriodogramResult
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramLayout
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import one.aozora.darkhour.ui.settings.AppSettings

@Immutable
data class SleepAnalysisState(
    val records: List<SleepRecord>,
    val analysis: CircadianAnalysis,
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

/** Session-only debug tuning. This deliberately has no persistence boundary. */
@Immutable
data class DeveloperCircadianState(
    val algorithmId: String,
    val overridesByAlgorithm: Map<String, Map<String, Double>>,
    val onAlgorithmChange: (String) -> Unit,
    val onParameterChange: (String, Double) -> Unit,
    val onParameterReset: (String) -> Unit,
) {
    val activeOverrides: Map<String, Double>
        get() = overridesByAlgorithm[algorithmId].orEmpty()

    val activeAlgorithm
        get() = CircadianAlgorithmRegistry.algorithm(algorithmId)
}

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
    val statsAllRecords: List<SleepRecord>?,
    val isRefreshing: Boolean,
    val isStatsAllDataRefreshing: Boolean,
    val importedRecordCount: Int,
    val expectedRecordCount: Int?,
    val isImportPartial: Boolean,
    val importPhase: HealthImportPhase,
    val importError: String?,
    val statsAllDataError: String?,
    val totalHistoryDays: Int?,
    val onRequestHealthPermissions: () -> Unit,
    val onRequestHistoryPermission: () -> Unit,
    val onRequestStatsAllData: () -> Unit,
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

val LocalDeveloperCircadian = staticCompositionLocalOf<DeveloperCircadianState> {
    error("LocalDeveloperCircadian was not provided")
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
    developerCircadian: DeveloperCircadianState,
    schedule: ScheduleState,
    healthConnect: HealthConnectState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSleepAnalysis provides sleepAnalysis,
        LocalAppSettings provides appSettings,
        LocalActogramDisplay provides actogramDisplay,
        LocalDeveloperCircadian provides developerCircadian,
        LocalScheduleState provides schedule,
        LocalHealthConnectState provides healthConnect,
        content = content,
    )
}
