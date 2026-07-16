package one.aozora.darkhour

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import one.aozora.darkhour.data.HealthConnectDataController
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HEALTH_CONNECT_PROVIDER_PACKAGE_NAME
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.shouldOfferHealthConnectSetup
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
    private var lastHealthPermissionRequest: PendingHealthPermissionRequest? = null
    private var resumePermissionRequestAfterSetup: PendingHealthPermissionRequest? = null
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) { granted ->
        if (shouldOfferHealthConnectSetup(
                grantedPermissions = granted,
                providerAvailable = healthConnect.isProviderAvailable,
                resumedAfterSetup = lastHealthPermissionRequest?.resumedAfterSetup == true,
            )
        ) {
            healthConnect.reportSetupRequired()
        } else {
            lastHealthPermissionRequest = null
            val exportRange = pendingSleepExportRange
            pendingSleepExportRange = null
            if (exportRange != null) {
                if (granted.containsAll(healthConnect.exportPermissions(exportRange))) {
                    healthConnect.prepareSleepExport(exportRange)
                } else {
                    healthConnect.reportSleepExportPermissionDenied()
                }
            }
            healthConnect.clearSetupRequired()
            healthConnect.refresh()
        }
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
        if (HealthConnectDataController.sleepWritePermission in granted && action != null) {
            pendingSleepWriteAction = null
            lastHealthPermissionRequest = null
            runSleepWriteAction(action)
        } else if (shouldOfferHealthConnectSetup(
                grantedPermissions = granted,
                providerAvailable = healthConnect.isProviderAvailable,
                resumedAfterSetup = lastHealthPermissionRequest?.resumedAfterSetup == true,
            )
        ) {
            healthConnect.reportSetupRequired()
        } else if (::healthConnect.isInitialized) {
            pendingSleepWriteAction = null
            lastHealthPermissionRequest = null
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
            allowLegacyFileImport = BuildConfig.DEBUG,
        )
        enableEdgeToEdge()
        setContent {
            val healthState by healthConnect.state.collectAsState()
            val initialSettings = remember { appSettings.read() }
            val initialDisplayOptions = remember { startupDisplayOptions }
            val initialScheduleEntries = remember { appSettings.readScheduleEntries() }
            val records = if (BuildConfig.USE_DEMO_DATA) DemoData.records else healthState.records
            val recordMetadata = if (BuildConfig.USE_DEMO_DATA) {
                emptyMap()
            } else {
                healthState.recordMetadata
            }
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
                    recordMetadata = recordMetadata,
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
                    fileDeletionSupported = !BuildConfig.USE_DEMO_DATA && healthState.fileDeletionSupported,
                    fileImportedRecordCount = healthState.fileImportedRecordCount,
                    fileOperation = healthState.fileOperation,
                    fileImportResult = healthState.fileImportResult,
                    exportPreparation = healthState.exportPreparation,
                    exportResult = healthState.exportResult,
                    fileOperationMessage = healthState.fileOperationMessage,
                    fileOperationError = healthState.fileOperationErrorMessage,
                    onRequestHealthPermissions = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            launchHealthConnectPermissions(healthState.dataRange)
                        }
                    },
                    onRequestHistoryPermission = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            launchHealthConnectPermissions(HealthDataRange.ENTIRE_HISTORY)
                        }
                    },
                    onInstallHealthConnect = ::openHealthConnectProviderListing,
                    onOpenHealthConnect = ::openHealthConnect,
                    onRequestStatsAllData = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            healthConnect.refreshStatsAllData()
                        }
                    },
                    onHealthDataRangeChange = { range ->
                        if (BuildConfig.USE_DEMO_DATA) {
                            appSettings.writeHealthDataRange(range)
                        } else if (healthConnect.isProviderAvailable) {
                            appSettings.writeHealthDataRange(range)
                            healthConnect.setDataRange(range)
                            if (
                                range.requiresHistoryPermission &&
                                range != healthState.dataRange
                            ) {
                                launchHealthConnectPermissions(range)
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
                        if (BuildConfig.USE_DEMO_DATA || healthConnect.isProviderAvailable) {
                            pendingSleepExportPackages = packages
                            createSleepExport.launch("darkhour-health-connect-sleep-${java.time.LocalDate.now()}.json")
                        }
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
            resumePermissionRequestAfterSetup?.let { request ->
                resumePermissionRequestAfterSetup = null
                launchHealthPermissionRequest(request.copy(resumedAfterSetup = true))
            }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) {
            AdaptiveKalmanDiagnostics.logger = null
        }
        super.onDestroy()
    }

    private fun beginSleepWriteAction(action: SleepWriteAction) {
        if (BuildConfig.USE_DEMO_DATA || !::healthConnect.isInitialized) return
        val actionSupported = when (action) {
            SleepWriteAction.IMPORT -> healthConnect.isFileWriteSupported
            SleepWriteAction.DELETE -> healthConnect.isFileDeletionSupported
        }
        if (
            !healthConnect.isProviderAvailable ||
            !actionSupported
        ) {
            return
        }
        lifecycleScope.launch {
            if (healthConnect.hasSleepWritePermission()) {
                runSleepWriteAction(action)
            } else if (healthConnect.isProviderAvailable) {
                pendingSleepWriteAction = action
                launchHealthPermissionRequest(
                    PendingHealthPermissionRequest(
                        permissions = setOf(HealthConnectDataController.sleepWritePermission),
                        kind = HealthPermissionRequestKind.SLEEP_WRITE,
                    ),
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
        if (
            BuildConfig.USE_DEMO_DATA ||
            !::healthConnect.isInitialized ||
            !healthConnect.isProviderAvailable
        ) {
            return
        }
        lifecycleScope.launch {
            val permissions = healthConnect.exportPermissions(range)
            if (healthConnect.hasPermissions(permissions)) {
                healthConnect.prepareSleepExport(range)
            } else {
                healthConnect.cancelSleepExport()
                pendingSleepExportRange = range
                launchHealthConnectPermissions(permissions)
            }
        }
    }

    private fun launchHealthConnectPermissions(range: HealthDataRange) {
        launchHealthConnectPermissions(healthConnect.requiredPermissions(range))
    }

    private fun launchHealthConnectPermissions(permissions: Set<String>) {
        launchHealthPermissionRequest(
            PendingHealthPermissionRequest(
                permissions = permissions,
                kind = HealthPermissionRequestKind.GENERAL,
            ),
        )
    }

    private fun launchHealthPermissionRequest(request: PendingHealthPermissionRequest) {
        if (!::healthConnect.isInitialized || !healthConnect.isProviderAvailable) {
            if (::healthConnect.isInitialized) healthConnect.refresh()
            return
        }
        lastHealthPermissionRequest = request
        runCatching {
            when (request.kind) {
                HealthPermissionRequestKind.GENERAL ->
                    requestHealthPermissions.launch(request.permissions)
                HealthPermissionRequestKind.SLEEP_WRITE ->
                    requestSleepWritePermission.launch(request.permissions)
            }
        }.onFailure {
            lastHealthPermissionRequest = null
            when (request.kind) {
                HealthPermissionRequestKind.GENERAL -> pendingSleepExportRange = null
                HealthPermissionRequestKind.SLEEP_WRITE -> pendingSleepWriteAction = null
            }
            healthConnect.refresh()
        }
    }

    private fun openHealthConnectProviderListing() {
        val marketUri = Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE_NAME")
        val webUri = Uri.parse(
            "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE_NAME",
        )
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            setPackage(GOOGLE_PLAY_PACKAGE_NAME)
        }
        if (runCatching { startActivity(marketIntent) }.isFailure) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
        }
    }

    private fun openHealthConnect() {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackage(HEALTH_CONNECT_PROVIDER_PACKAGE_NAME)
            }
        }
        if (runCatching { startActivity(intent) }.isSuccess) {
            resumePermissionRequestAfterSetup = lastHealthPermissionRequest
            healthConnect.clearSetupRequired()
        } else {
            openHealthConnectProviderListing()
        }
    }
}

private enum class SleepWriteAction {
    IMPORT,
    DELETE,
}

private data class PendingHealthPermissionRequest(
    val permissions: Set<String>,
    val kind: HealthPermissionRequestKind,
    val resumedAfterSetup: Boolean = false,
)

private enum class HealthPermissionRequestKind {
    GENERAL,
    SLEEP_WRITE,
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
private const val GOOGLE_PLAY_PACKAGE_NAME = "com.android.vending"
