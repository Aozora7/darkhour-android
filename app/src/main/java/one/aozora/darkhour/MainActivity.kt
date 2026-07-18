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
import one.aozora.darkhour.data.HistoryPermissionState
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.data.SleepFileDecoderRegistry
import one.aozora.darkhour.data.shouldOfferHealthConnectSetup
import one.aozora.darkhour.data.settings.AppSettingsStore
import one.aozora.darkhour.core.circadian.adaptive.AdaptiveKalmanDiagnostics
import one.aozora.darkhour.ui.DarkHourApp
import one.aozora.darkhour.ui.DemoData
import one.aozora.darkhour.ui.theme.DarkHourTheme

class MainActivity : ComponentActivity() {
    private lateinit var healthConnect: HealthConnectDataController
    private lateinit var appSettings: AppSettingsStore
    private var pendingSleepWriteAction: SleepWriteAction? = null
    private var pendingSleepExportRange: SleepExportRange? = null
    private var pendingSleepExportPackages: Set<String>? = null
    private var lastHealthPermissionRequest: PendingHealthPermissionRequest? = null
    private var resumePermissionRequestAfterSetup: PendingHealthPermissionRequest? = null
    private val sleepFileDecoderRegistry = SleepFileDecoderRegistry()
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) { granted ->
        val requestKind = lastHealthPermissionRequest?.kind
        if (requestKind == HealthPermissionRequestKind.GENERAL && shouldOfferHealthConnectSetup(
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
                if (granted.containsAll(healthConnect.exportPermissions())) {
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
            initialImportDuration = initialVisibleImportDuration(
                viewportHeightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density,
                options = startupDisplayOptions,
            ),
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
                    historyPermissionState = if (BuildConfig.USE_DEMO_DATA) {
                        HistoryPermissionState.GRANTED
                    } else {
                        healthState.historyPermissionState
                    },
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
                    availableHistoryDays = healthState.availableHistoryDays,
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
                            launchHealthConnectPermissions()
                        }
                    },
                    onRequestHistoryPermission = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            val permissions = healthConnect.historyPermissionRequest()
                            if (permissions.isNotEmpty()) launchHistoryPermissionRequest(permissions)
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
            SleepWriteAction.IMPORT -> openSleepFiles.launch(
                sleepFileDecoderRegistry.supportedMimeTypes.toTypedArray(),
            )
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
            val permissions = healthConnect.exportPermissions()
            if (healthConnect.hasPermissions(permissions)) {
                healthConnect.prepareSleepExport(range)
            } else {
                healthConnect.cancelSleepExport()
                pendingSleepExportRange = range
                launchHealthConnectPermissions(permissions)
            }
        }
    }

    private fun launchHealthConnectPermissions() {
        launchHealthConnectPermissions(healthConnect.requiredPermissions())
    }

    private fun launchHealthConnectPermissions(permissions: Set<String>) {
        launchHealthPermissionRequest(
            PendingHealthPermissionRequest(
                permissions = permissions,
                kind = HealthPermissionRequestKind.GENERAL,
            ),
        )
    }

    private fun launchHistoryPermissionRequest(permissions: Set<String>) {
        launchHealthPermissionRequest(
            PendingHealthPermissionRequest(
                permissions = permissions,
                kind = HealthPermissionRequestKind.HISTORY,
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
                HealthPermissionRequestKind.GENERAL,
                HealthPermissionRequestKind.HISTORY,
                ->
                    requestHealthPermissions.launch(request.permissions)
                HealthPermissionRequestKind.SLEEP_WRITE ->
                    requestSleepWritePermission.launch(request.permissions)
            }
        }.onFailure {
            lastHealthPermissionRequest = null
            when (request.kind) {
                HealthPermissionRequestKind.GENERAL -> pendingSleepExportRange = null
                HealthPermissionRequestKind.HISTORY -> Unit
                HealthPermissionRequestKind.SLEEP_WRITE -> pendingSleepWriteAction = null
            }
            healthConnect.refresh()
        }
    }

    private fun openHealthConnectProviderListing() {
        val providerPackageName = getString(R.string.health_connect_provider_package_name)
        val marketUri = Uri.parse("market://details?id=$providerPackageName")
        val webUri = Uri.parse(
            "https://play.google.com/store/apps/details?id=$providerPackageName",
        )
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            setPackage(getString(R.string.google_play_package_name))
        }
        if (runCatching { startActivity(marketIntent) }.isFailure) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
        }
    }

    private fun openHealthConnect() {
        val providerPackageName = getString(R.string.health_connect_provider_package_name)
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackage(providerPackageName)
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
    HISTORY,
    SLEEP_WRITE,
}

private const val ADAPTIVE_KALMAN_LOG_TAG = "DarkHourAdaptiveKalman"
