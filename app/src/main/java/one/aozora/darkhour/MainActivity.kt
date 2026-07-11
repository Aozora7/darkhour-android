package one.aozora.darkhour

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import one.aozora.darkhour.data.HealthConnectDataController
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.settings.AppSettingsStore
import one.aozora.darkhour.core.circadian.kalman.KalmanChangeDetectionDiagnostics
import one.aozora.darkhour.core.circadian.kalman.SwitchingKalmanDiagnostics
import one.aozora.darkhour.ui.DarkHourApp
import one.aozora.darkhour.ui.DemoData
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import one.aozora.darkhour.ui.theme.DarkHourTheme
import java.time.Duration
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    private lateinit var healthConnect: HealthConnectDataController
    private lateinit var appSettings: AppSettingsStore
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) {
        healthConnect.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            KalmanChangeDetectionDiagnostics.logger = { message ->
                Log.d(KALMAN_CHANGE_LOG_TAG, message)
            }
            SwitchingKalmanDiagnostics.logger = { message ->
                Log.d(SWITCHING_KALMAN_LOG_TAG, message)
            }
        }
        appSettings = AppSettingsStore(this)
        val startupDisplayOptions = appSettings.readDisplayOptions()
        healthConnect = HealthConnectDataController(
            context = this,
            scope = lifecycleScope,
            initialDataRange = appSettings.readHealthDataRange(),
            initialImportDuration = initialVisibleImportDuration(startupDisplayOptions),
        )
        enableEdgeToEdge()
        setContent {
            val healthState by healthConnect.state.collectAsState()
            val initialSettings = remember { appSettings.read() }
            val initialDisplayOptions = remember { startupDisplayOptions }
            val initialScheduleEntries = remember { appSettings.readScheduleEntries() }
            val records = if (BuildConfig.USE_DEMO_DATA) DemoData.records else healthState.records
            val healthConnectAccess = if (BuildConfig.USE_DEMO_DATA) {
                one.aozora.darkhour.data.HealthConnectAccess.CONNECTED
            } else {
                healthState.access
            }
            DarkHourTheme {
                DarkHourApp(
                    records = records,
                    initialSettings = initialSettings,
                    onAppSettingsChange = appSettings::write,
                    initialDisplayOptions = initialDisplayOptions,
                    onDisplayOptionsChange = appSettings::writeDisplayOptions,
                    initialScheduleEntries = initialScheduleEntries,
                    onScheduleEntriesChange = appSettings::writeScheduleEntries,
                    healthConnectAccess = healthConnectAccess,
                    healthDataRange = healthState.dataRange,
                    hasHistoryPermission = BuildConfig.USE_DEMO_DATA || healthState.hasHistoryPermission,
                    statsAllRecords = if (BuildConfig.USE_DEMO_DATA) DemoData.records else healthState.statsAllRecords,
                    isRefreshing = !BuildConfig.USE_DEMO_DATA && healthState.isRefreshing,
                    isStatsAllDataRefreshing = !BuildConfig.USE_DEMO_DATA && healthState.isStatsAllDataRefreshing,
                    importedRecordCount = healthState.importedRecordCount,
                    expectedRecordCount = healthState.expectedRecordCount,
                    isImportPartial = healthState.isImportPartial,
                    importPhase = healthState.importPhase,
                    importError = if (BuildConfig.USE_DEMO_DATA) null else healthState.errorMessage,
                    statsAllDataError = if (BuildConfig.USE_DEMO_DATA) null else healthState.statsAllDataErrorMessage,
                    totalHistoryDays = healthState.totalHistoryDays,
                    onRequestHealthPermissions = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            requestHealthPermissions.launch(healthConnect.requiredPermissions())
                        }
                    },
                    onRequestHistoryPermission = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            requestHealthPermissions.launch(
                                healthConnect.requiredPermissions(HealthDataRange.ENTIRE_HISTORY),
                            )
                        }
                    },
                    onRequestStatsAllData = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            healthConnect.refreshStatsAllData()
                        }
                    },
                    onHealthDataRangeChange = { range ->
                        appSettings.writeHealthDataRange(range)
                        if (!BuildConfig.USE_DEMO_DATA) {
                            healthConnect.setDataRange(range)
                            if (
                                range.requiresHistoryPermission &&
                                range != healthState.dataRange
                            ) {
                                requestHealthPermissions.launch(healthConnect.requiredPermissions(range))
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::healthConnect.isInitialized && !BuildConfig.USE_DEMO_DATA) {
            healthConnect.refresh()
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) {
            KalmanChangeDetectionDiagnostics.logger = null
            SwitchingKalmanDiagnostics.logger = null
        }
        super.onDestroy()
    }
}

private fun ComponentActivity.initialVisibleImportDuration(
    options: ActogramDisplayOptions,
): Duration {
    val heightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
    val visibleRows = ceil(
        ((heightDp - ACTOGRAM_AXIS_HEIGHT_DP).coerceAtLeast(0f) / options.rowHeightDp)
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
private const val KALMAN_CHANGE_LOG_TAG = "DarkHourKalmanChange"
private const val SWITCHING_KALMAN_LOG_TAG = "DarkHourSwitchingKalman"
