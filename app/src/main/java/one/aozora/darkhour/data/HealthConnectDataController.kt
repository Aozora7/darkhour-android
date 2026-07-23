package one.aozora.darkhour.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class HealthConnectDataController(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    initialDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    initialImportDuration: Duration = DEFAULT_HISTORY_DURATION,
    allowLegacyFileImport: Boolean = false,
) {
    private val applicationContext = context.applicationContext
    private val healthConnectAppNames by lazy {
        healthConnectAppDisplayNames(applicationContext)
    }
    private val zoneId = ZoneId.systemDefault()
    private val allowLegacyDirectFileImport = allowLegacyFileImport
    private val initialFileCapabilities = sleepFileCapabilities(
        sdkInt = Build.VERSION.SDK_INT,
        historyPermissionState = HistoryPermissionState.UNAVAILABLE,
        allowLegacyFileImport = allowLegacyDirectFileImport,
    )
    private var recentImportDuration = initialImportDuration.coerceAtLeast(Duration.ofDays(1))
    private var refreshJob: Job? = null
    private var statsAllDataRefreshJob: Job? = null
    private var fileOperationJob: Job? = null
    private var setupRequired = false
    private val mutableState = MutableStateFlow(
        HealthConnectUiState(
            dataRange = initialDataRange,
            fileWriteSupported = initialFileCapabilities.importSupported,
            fileDeletionSupported = initialFileCapabilities.deletionSupported,
        ),
    )

    val state: StateFlow<HealthConnectUiState> = mutableState.asStateFlow()

    val isFileWriteSupported: Boolean
        get() = state.value.fileWriteSupported

    val isFileDeletionSupported: Boolean
        get() = state.value.fileDeletionSupported

    val isProviderAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE

    fun requiredPermissions(): Set<String> = setOf(SLEEP_READ_PERMISSION)

    fun historyPermissionRequest(): Set<String> =
        if (state.value.historyPermissionState.canRequestPermission) {
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        } else {
            emptySet()
        }

    fun fileImportPermissions(): Set<String> =
        sleepFileImportPermissions(currentFileCapabilities())

    fun fileImportReadPermissions(): Set<String> =
        if (currentFileCapabilities().usesLegacyDirectImport) {
            emptySet()
        } else {
            requiredPermissions()
        }

    fun fileImportHistoryPermissions(): Set<String> =
        if (currentFileCapabilities().importRequiresHistoryPermission) {
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        } else {
            emptySet()
        }

    fun fileDeletionPermissions(): Set<String> = setOf(SLEEP_WRITE_PERMISSION)

    suspend fun grantedPermissions(): Set<String> {
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            return emptySet()
        }
        return HealthConnectClient.getOrCreate(applicationContext)
            .permissionController
            .getGrantedPermissions()
    }

    suspend fun hasPermissions(permissions: Set<String>): Boolean {
        return grantedPermissions().containsAll(permissions)
    }

    fun exportPermissions(): Set<String> = requiredPermissions()

    fun reportSleepWritePermissionDenied() {
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                fileOperationErrorMessage = "Sleep write permission was not granted",
            )
        }
    }

    fun reportSleepFileImportHistoryPermissionDenied() {
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                fileOperationErrorMessage =
                    "History access was not granted; sleep files were not imported",
            )
        }
    }

    fun reportSleepFileImportReadPermissionDenied() {
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                fileOperationErrorMessage =
                    "Sleep read permission was not granted; sleep files were not imported",
            )
        }
    }

    fun reportSleepExportPermissionDenied() {
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                fileOperationErrorMessage = "Sleep read permission was not granted",
            )
        }
    }

    fun reportSetupRequired() {
        setupRequired = true
        mutableState.update {
            it.copy(
                access = HealthConnectAccess.SETUP_REQUIRED,
                isRefreshing = false,
                errorMessage = null,
            )
        }
    }

    fun clearSetupRequired() {
        setupRequired = false
    }

    fun importSleepFiles(uris: List<Uri>) {
        if (uris.isEmpty() || !beginFileOperation(HealthConnectFileOperation.IMPORTING)) return
        fileOperationJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    importSleepFilesToHealthConnect(
                        client = HealthConnectClient.getOrCreate(applicationContext),
                        contentResolver = applicationContext.contentResolver,
                        uris = uris,
                        fallbackZoneId = ZoneId.systemDefault(),
                        callingPackageName = applicationContext.packageName,
                        verifyExistingHealthConnectRecords = !currentFileCapabilities()
                            .usesLegacyDirectImport,
                    )
                }
                mutableState.update {
                    it.copy(
                        statsAllRecords = null,
                        fileOperation = HealthConnectFileOperation.IDLE,
                        fileImportResult = result,
                        fileOperationMessage = result.summaryText(),
                        fileOperationErrorMessage = result.errorMessage,
                    )
                }
                if (result.committedRecordCount > 0) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                finishFileOperationWithError(failure, "Sleep file import failed")
            }
        }
    }

    fun prepareSleepExport(range: SleepExportRange) {
        if (!isProviderAvailable || !beginPreparingExport()) return
        fileOperationJob?.cancel()
        fileOperationJob = scope.launch {
            try {
                val preparation = withContext(Dispatchers.IO) {
                    HealthConnectClient.getOrCreate(applicationContext).prepareSleepExport(
                        range = range,
                        ownedPackageName = applicationContext.packageName,
                        hasCompleteHistoryAccess = state.value.historyPermissionState
                            .hasCompleteHistoryAccess,
                        packageDisplayName = ::packageDisplayName,
                    )
                }
                mutableState.update {
                    it.copy(
                        fileOperation = HealthConnectFileOperation.IDLE,
                        exportPreparation = preparation,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                finishFileOperationWithError(failure, "Could not prepare sleep export")
            }
        }
    }

    fun cancelSleepExport() {
        if (state.value.fileOperation == HealthConnectFileOperation.PREPARING_EXPORT) {
            fileOperationJob?.cancel()
        }
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                exportPreparation = null,
            )
        }
    }

    fun exportSleepRecords(uri: Uri, packageNames: Set<String>) {
        val preparation = state.value.exportPreparation ?: return
        if (packageNames.isEmpty() || !beginFileOperation(
                HealthConnectFileOperation.EXPORTING,
                requiresWriteSupport = false,
            )
        ) {
            return
        }
        fileOperationJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    HealthConnectClient.getOrCreate(applicationContext).writeSleepExport(
                        contentResolver = applicationContext.contentResolver,
                        uri = uri,
                        range = preparation.range,
                        packageNames = packageNames,
                        ownedPackageName = applicationContext.packageName,
                        hasCompleteHistoryAccess = state.value.historyPermissionState
                            .hasCompleteHistoryAccess,
                        clock = clock,
                    )
                }
                mutableState.update {
                    it.copy(
                        fileOperation = HealthConnectFileOperation.IDLE,
                        exportPreparation = null,
                        exportResult = result,
                        fileOperationMessage = result.summaryText(),
                        fileOperationErrorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runCatching { applicationContext.contentResolver.delete(uri, null, null) }
                finishFileOperationWithError(failure, "Sleep export failed")
            }
        }
    }

    fun deleteOwnedSleepRecords() {
        if (!beginFileOperation(HealthConnectFileOperation.DELETING)) return
        fileOperationJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HealthConnectClient.getOrCreate(applicationContext).deleteRecords(
                        SleepSessionRecord::class,
                        TimeRangeFilter.before(MAX_HEALTH_CONNECT_EPOCH_MILLI_INSTANT),
                    )
                }
                mutableState.update {
                    it.copy(
                        statsAllRecords = null,
                        fileImportedRecordCount = 0,
                        totalHistoryDays = null,
                        fileOperation = HealthConnectFileOperation.IDLE,
                        fileImportResult = null,
                        fileOperationMessage = "Deleted imported records",
                        fileOperationErrorMessage = null,
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                finishFileOperationWithError(failure, "Imported record deletion failed")
            }
        }
    }

    private fun beginFileOperation(
        operation: HealthConnectFileOperation,
        requiresWriteSupport: Boolean = true,
    ): Boolean {
        val operationWriteSupported = when (operation) {
            HealthConnectFileOperation.IMPORTING -> state.value.fileWriteSupported
            HealthConnectFileOperation.DELETING -> state.value.fileDeletionSupported
            else -> true
        }
        if (!isProviderAvailable || (requiresWriteSupport && !operationWriteSupported)) {
            return false
        }
        while (true) {
            val current = mutableState.value
            if (current.fileOperation != HealthConnectFileOperation.IDLE) return false
            if (mutableState.compareAndSet(current, current.withFileOperationStarted(operation))) {
                return true
            }
        }
    }

    private fun beginPreparingExport(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.fileOperation != HealthConnectFileOperation.IDLE &&
                current.fileOperation != HealthConnectFileOperation.PREPARING_EXPORT
            ) {
                return false
            }
            val preparing = current.withFileOperationStarted(
                HealthConnectFileOperation.PREPARING_EXPORT,
            )
            if (mutableState.compareAndSet(current, preparing)) return true
        }
    }

    private fun finishFileOperationWithError(failure: Exception, fallback: String) {
        mutableState.update {
            it.copy(
                fileOperation = HealthConnectFileOperation.IDLE,
                fileOperationErrorMessage = failure.message ?: fallback,
            )
        }
    }

    private fun packageDisplayName(packageName: String): String =
        healthConnectAppNames[packageName] ?: runCatching {
            val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
            applicationContext.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    fun setDataRange(range: HealthDataRange) {
        if (range == state.value.dataRange) return
        mutableState.update { it.copy(dataRange = range, errorMessage = null) }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val range = state.value.dataRange
                when (val sdkStatus = HealthConnectClient.getSdkStatus(applicationContext)) {
                    HealthConnectClient.SDK_UNAVAILABLE -> {
                        setupRequired = false
                        mutableState.update {
                            it.withProviderUnavailable(HealthConnectAccess.UNAVAILABLE)
                        }
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        setupRequired = false
                        mutableState.update {
                            it.withProviderUnavailable(
                                healthConnectAccess(applicationContext, sdkStatus),
                            )
                        }
                    }
                    HealthConnectClient.SDK_AVAILABLE -> {
                        if (setupRequired) {
                            mutableState.update {
                                it.copy(
                                    access = HealthConnectAccess.SETUP_REQUIRED,
                                    isRefreshing = false,
                                    errorMessage = null,
                                )
                            }
                        } else {
                            refreshAvailableClient(range)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = failure.message ?: "Health Connect refresh failed",
                    )
                }
            }
        }
    }

    fun refreshStatsAllData() {
        statsAllDataRefreshJob?.cancel()
        statsAllDataRefreshJob = scope.launch {
            try {
                when (HealthConnectClient.getSdkStatus(applicationContext)) {
                    HealthConnectClient.SDK_UNAVAILABLE,
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        mutableState.update(HealthConnectUiState::withStatsUnavailable)
                    }
                    HealthConnectClient.SDK_AVAILABLE -> refreshStatsAllDataAvailableClient()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.withStatsRefreshFailure(failure.message ?: "Stats import failed")
                }
            }
        }
    }

    private suspend fun refreshAvailableClient(range: HealthDataRange) {
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        val historyPermissionState = client.historyPermissionState(granted)
        updateFileCapabilities(historyPermissionState, granted)
        if (!granted.containsAll(requiredPermissions())) {
            mutableState.update { it.withReadPermissionRequired(historyPermissionState) }
            return
        }

        mutableState.update { it.withRefreshStarted(historyPermissionState) }
        try {
            val now = clock.instant()
            val imported = withContext(Dispatchers.IO) {
                client.readSleepRecords(
                    range = range,
                    now = now,
                    zoneId = zoneId,
                    ownedPackageName = applicationContext.packageName,
                    initialImportDuration = recentImportDuration,
                    hasCompleteHistoryAccess = historyPermissionState.hasCompleteHistoryAccess,
                    onProgress = ::publishImportProgress,
                )
            }
            val records = imported.records
                .map(ImportedSleepRecord::record)
                .sortedBy(SleepRecord::startTime)
            val analysisRecords = imported.analysisRecords
                .map(ImportedSleepRecord::record)
                .sortedBy(SleepRecord::startTime)
            val recordMetadata = imported.records.displayMetadataByLogId(::packageDisplayName)
            val importedAvailableHistoryDays = totalHistoryDaysFromOldest(
                imported.records.minOfOrNull { it.record.startTime },
                now,
                zoneId,
            ) ?: HealthDataRange.MINIMUM_CUSTOM_DAYS
            mutableState.update {
                it.withRefreshCompleted(
                    records = records,
                    recordMetadata = recordMetadata,
                    analysisRecords = analysisRecords,
                    importedTotalHistoryDays = imported.totalHistoryDays,
                    importedAvailableHistoryDays = importedAvailableHistoryDays,
                    historyPermissionState = historyPermissionState,
                    fileImportedRecordCount = imported.fileImportedRecordCount,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.withRefreshFailure(failure.message ?: "Health Connect import failed")
            }
        }
    }

    private suspend fun refreshStatsAllDataAvailableClient() {
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        val historyPermissionState = client.historyPermissionState(granted)
        if (!granted.containsAll(requiredPermissions())) {
            mutableState.update {
                it.withStatsPermissionRequired(historyPermissionState)
            }
            return
        }

        mutableState.update { it.withStatsRefreshStarted(historyPermissionState) }
        try {
            val now = clock.instant()
            val imported = withContext(Dispatchers.IO) {
                client.readSleepRecords(
                    range = HealthDataRange.ENTIRE_HISTORY,
                    now = now,
                    zoneId = zoneId,
                    ownedPackageName = applicationContext.packageName,
                    initialImportDuration = recentImportDuration,
                    hasCompleteHistoryAccess = historyPermissionState.hasCompleteHistoryAccess,
                )
            }
            val records = imported.analysisRecords
                .map(ImportedSleepRecord::record)
                .sortedBy(SleepRecord::startTime)
            val importedAvailableHistoryDays = totalHistoryDaysFromOldest(
                imported.records.minOfOrNull { it.record.startTime },
                now,
                zoneId,
            ) ?: HealthDataRange.MINIMUM_CUSTOM_DAYS
            mutableState.update {
                it.withStatsRefreshCompleted(
                    records,
                    imported.totalHistoryDays,
                    importedAvailableHistoryDays,
                    historyPermissionState,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.withStatsRefreshFailure(failure.message ?: "Stats import failed")
            }
        }
    }

    private fun publishImportProgress(progress: HealthImportProgress) {
        val progressRecords = progress.records
            ?.map(ImportedSleepRecord::record)
            ?.sortedBy(SleepRecord::startTime)
        val progressAnalysisRecords = progress.analysisRecords
            ?.map(ImportedSleepRecord::record)
            ?.sortedBy(SleepRecord::startTime)
        val progressRecordMetadata = progress.records
            ?.displayMetadataByLogId(::packageDisplayName)
        mutableState.update {
            it.withImportProgress(
                records = progressRecords,
                recordMetadata = progressRecordMetadata,
                analysisRecords = progressAnalysisRecords,
                importedRecordCount = progress.importedRecordCount,
                expectedRecordCount = progress.expectedRecordCount,
                isImportPartial = progress.isImportPartial,
                phase = progress.phase,
            )
        }
    }

    private fun currentFileCapabilities(): SleepFileCapabilities =
        sleepFileCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            historyPermissionState = state.value.historyPermissionState,
            allowLegacyFileImport = allowLegacyDirectFileImport,
        )

    private fun updateFileCapabilities(
        historyPermissionState: HistoryPermissionState,
        grantedPermissions: Set<String>,
    ) {
        val capabilities = sleepFileCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            historyPermissionState = historyPermissionState,
            allowLegacyFileImport = allowLegacyDirectFileImport,
        )
        mutableState.update {
            it.copy(
                fileWriteSupported = capabilities.importSupported,
                fileDeletionSupported = capabilities.deletionSupported,
                fileImportPermissionsGranted = grantedPermissions.containsAll(
                    sleepFileImportPermissions(capabilities),
                ),
            )
        }
    }

    companion object {
        val permissionContract
            get() = PermissionController.createRequestPermissionResultContract()

        private val SLEEP_READ_PERMISSION =
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        private val SLEEP_WRITE_PERMISSION =
            HealthPermission.getWritePermission(SleepSessionRecord::class)
    }
}

internal fun HealthConnectClient.historyPermissionState(
    grantedPermissions: Set<String>,
): HistoryPermissionState = historyPermissionState(
    grantedPermissions = grantedPermissions,
    featureAvailable = features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
)

internal fun historyPermissionState(
    grantedPermissions: Set<String>,
    featureAvailable: Boolean,
): HistoryPermissionState = when {
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions ->
        HistoryPermissionState.GRANTED
    featureAvailable -> HistoryPermissionState.AVAILABLE_NOT_GRANTED
    else -> HistoryPermissionState.UNAVAILABLE
}

/**
 * Health Connect transports instant bounds as epoch milliseconds. [Instant.MAX] cannot be
 * represented by a [Long] in that form and causes `long overflow` before the provider is called.
 */
internal val MAX_HEALTH_CONNECT_EPOCH_MILLI_INSTANT: Instant =
    Instant.ofEpochMilli(Long.MAX_VALUE)

internal fun isLegacyDirectFileImportSupported(
    allowLegacyFileImport: Boolean,
    sdkInt: Int,
): Boolean = allowLegacyFileImport &&
    sdkInt in Build.VERSION_CODES.P until Build.VERSION_CODES.UPSIDE_DOWN_CAKE

internal data class SleepFileCapabilities(
    val importSupported: Boolean,
    val deletionSupported: Boolean,
    val usesLegacyDirectImport: Boolean,
    val importRequiresHistoryPermission: Boolean,
)

internal fun sleepFileCapabilities(
    sdkInt: Int,
    historyPermissionState: HistoryPermissionState,
    allowLegacyFileImport: Boolean,
): SleepFileCapabilities {
    if (historyPermissionState != HistoryPermissionState.UNAVAILABLE) {
        return SleepFileCapabilities(
            importSupported = true,
            deletionSupported = true,
            usesLegacyDirectImport = false,
            importRequiresHistoryPermission =
                sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
    }
    val legacyDirectImport = isLegacyDirectFileImportSupported(
        allowLegacyFileImport = allowLegacyFileImport,
        sdkInt = sdkInt,
    )
    return SleepFileCapabilities(
        importSupported = legacyDirectImport,
        deletionSupported = false,
        usesLegacyDirectImport = legacyDirectImport,
        importRequiresHistoryPermission = false,
    )
}

internal fun sleepFileImportPermissions(
    capabilities: SleepFileCapabilities,
): Set<String> = buildSet {
    if (!capabilities.usesLegacyDirectImport) {
        add(HealthPermission.getReadPermission(SleepSessionRecord::class))
    }
    add(HealthPermission.getWritePermission(SleepSessionRecord::class))
    if (capabilities.importRequiresHistoryPermission) {
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }
}
