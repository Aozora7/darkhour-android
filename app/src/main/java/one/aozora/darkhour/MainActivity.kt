package one.aozora.darkhour

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import one.aozora.darkhour.data.HealthConnectDataController
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.settings.AppSettingsStore
import one.aozora.darkhour.core.circadian.adaptive.AdaptiveKalmanDiagnostics
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
    private var pendingSleepWriteAction: SleepWriteAction? = null
    private var pendingSleepExportRange: SleepExportRange? = null
    private var pendingSleepExportPackages: Set<String>? = null
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) { granted ->
        val exportRange = pendingSleepExportRange
        pendingSleepExportRange = null
        if (exportRange != null) {
            if (granted.containsAll(healthConnect.exportPermissions(exportRange))) {
                healthConnect.prepareSleepExport(exportRange)
            } else {
                healthConnect.reportSleepExportPermissionDenied()
            }
        }
        healthConnect.refresh()
    }
    private val openSleepFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (::healthConnect.isInitialized && uris.isNotEmpty()) {
            healthConnect.importSleepFiles(uris)
        }
    }
    private val requestSleepWritePermission = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) { granted ->
        val action = pendingSleepWriteAction
        pendingSleepWriteAction = null
        if (HealthConnectDataController.sleepWritePermission in granted && action != null) {
            runSleepWriteAction(action)
        } else if (::healthConnect.isInitialized) {
            healthConnect.reportSleepWritePermissionDenied()
        }
    }
    private val createSleepExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val packages = pendingSleepExportPackages
        pendingSleepExportPackages = null
        if (uri != null && !packages.isNullOrEmpty()) {
            healthConnect.exportSleepRecords(uri, packages)
        } else if (::healthConnect.isInitialized) {
            healthConnect.cancelSleepExport()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            AdaptiveKalmanDiagnostics.logger = { message ->
                Log.d(ADAPTIVE_KALMAN_LOG_TAG, message)
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
            val analysisRecords = if (BuildConfig.USE_DEMO_DATA) {
                DemoData.records
            } else {
                healthState.analysisRecords
            }
            val healthConnectAccess = if (BuildConfig.USE_DEMO_DATA) {
                one.aozora.darkhour.data.HealthConnectAccess.CONNECTED
            } else {
                healthState.access
            }
            DarkHourTheme {
                DarkHourApp(
                    records = records,
                    analysisRecords = analysisRecords,
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
                    fileWriteSupported = !BuildConfig.USE_DEMO_DATA && healthState.fileWriteSupported,
                    fileImportedRecordCount = healthState.fileImportedRecordCount,
                    fileOperation = healthState.fileOperation,
                    fileImportResult = healthState.fileImportResult,
                    exportPreparation = healthState.exportPreparation,
                    exportResult = healthState.exportResult,
                    fileOperationMessage = healthState.fileOperationMessage,
                    fileOperationError = healthState.fileOperationErrorMessage,
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
                    onImportSleepFiles = {
                        beginSleepWriteAction(SleepWriteAction.IMPORT)
                    },
                    onDeleteOwnedSleepRecords = {
                        beginSleepWriteAction(SleepWriteAction.DELETE)
                    },
                    onPrepareSleepExport = ::beginSleepExportPreparation,
                    onCreateSleepExportDocument = { packages ->
                        pendingSleepExportPackages = packages
                        createSleepExport.launch("darkhour-health-connect-sleep-${java.time.LocalDate.now()}.json")
                    },
                    onCancelSleepExport = healthConnect::cancelSleepExport,
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
            AdaptiveKalmanDiagnostics.logger = null
        }
        super.onDestroy()
    }

    private fun beginSleepWriteAction(action: SleepWriteAction) {
        if (
            BuildConfig.USE_DEMO_DATA ||
            !::healthConnect.isInitialized ||
            !healthConnect.isFileWriteSupported
        ) {
            return
        }
        lifecycleScope.launch {
            if (healthConnect.hasSleepWritePermission()) {
                runSleepWriteAction(action)
            } else {
                pendingSleepWriteAction = action
                requestSleepWritePermission.launch(
                    setOf(HealthConnectDataController.sleepWritePermission),
                )
            }
        }
    }

    private fun runSleepWriteAction(action: SleepWriteAction) {
        when (action) {
            SleepWriteAction.IMPORT -> openSleepFiles.launch(arrayOf("*/*"))
            SleepWriteAction.DELETE -> healthConnect.deleteOwnedSleepRecords()
        }
    }

    private fun beginSleepExportPreparation(range: SleepExportRange) {
        if (BuildConfig.USE_DEMO_DATA || !::healthConnect.isInitialized) return
        lifecycleScope.launch {
            val permissions = healthConnect.exportPermissions(range)
            if (healthConnect.hasPermissions(permissions)) {
                healthConnect.prepareSleepExport(range)
            } else {
                healthConnect.cancelSleepExport()
                pendingSleepExportRange = range
                requestHealthPermissions.launch(permissions)
            }
        }
    }
}

private enum class SleepWriteAction {
    IMPORT,
    DELETE,
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
private const val ADAPTIVE_KALMAN_LOG_TAG = "DarkHourAdaptiveKalman"
