package one.aozora.darkhour.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthConnectFileOperation
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.SleepExportRange
import one.aozora.darkhour.ui.HealthConnectState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

@Composable
internal fun AnalysisSettingsSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingsSection("Analysis") {
        SettingsSwitch("Include naps", settings.includeNaps) {
            onSettingsChange(settings.copy(includeNaps = it))
        }
        Text("Forecast: ${settings.forecastDays} days", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = settings.forecastDays.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(forecastDays = it.roundToInt())) },
            valueRange = 0f..30f,
            steps = 29,
        )
    }
}

@Composable
internal fun AppearanceSettingsSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingsSection("Appearance") {
        SettingsSwitch(
            label = "ISO date and time",
            checked = settings.useIsoDateTime,
            testTag = "iso_date_time_toggle",
        ) {
            onSettingsChange(settings.copy(useIsoDateTime = it))
        }
        Text(
            if (settings.useIsoDateTime) {
                "Example: 2026-06-04 12:34"
            } else {
                "Example: Jun 4, 2026 12:34"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DataSettingsSection(
    healthConnect: HealthConnectState,
    healthDataRange: HealthDataRange,
    pendingCustomDays: Float,
    maxCustomDays: Int,
    visibleRecordCount: Int,
    onPendingCustomDaysChange: (Float) -> Unit,
) {
    val providerAvailable = healthConnect.access.providerAvailable
    SettingsSection("Data") {
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            HealthDataRangeOptions.forEachIndexed { index, option ->
                val selected = option.isSelected(healthDataRange)
                SegmentedButton(
                    selected = selected,
                    onClick = {
                        healthConnect.onDataRangeChange(
                            option.toRange(pendingCustomDays.roundToInt()),
                        )
                    },
                    enabled = providerAvailable,
                    shape = SegmentedButtonDefaults.itemShape(index, HealthDataRangeOptions.size),
                    modifier = Modifier.testTag(option.testTag),
                ) {
                    Text(option.label)
                }
            }
        }
        if (healthDataRange is HealthDataRange.Custom) {
            Text("Last ${pendingCustomDays.roundToInt()} days", style = MaterialTheme.typography.bodyLarge)
            if (maxCustomDays > HealthDataRange.MINIMUM_CUSTOM_DAYS) {
                Slider(
                    value = pendingCustomDays,
                    onValueChange = onPendingCustomDaysChange,
                    onValueChangeFinished = {
                        healthConnect.onDataRangeChange(
                            HealthDataRange.custom(pendingCustomDays.roundToInt()),
                        )
                    },
                    valueRange = HealthDataRange.MINIMUM_CUSTOM_DAYS.toFloat()..maxCustomDays.toFloat(),
                    steps = (maxCustomDays - HealthDataRange.MINIMUM_CUSTOM_DAYS - 1).coerceAtLeast(0),
                    modifier = Modifier.testTag("health_range_custom_days"),
                    enabled = providerAvailable,
                )
            }
        }
        Text(
            settingsImportStatusText(
                healthConnect = healthConnect,
                visibleRecordCount = visibleRecordCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (
            !healthConnect.hasHistoryPermission &&
            providerAvailable
        ) {
            OutlinedButton(
                onClick = healthConnect.onRequestHistoryPermission,
                modifier = Modifier.testTag("request_history_permission"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Allow history access")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportExportSettingsSection(
    healthConnect: HealthConnectState,
    customDays: Int,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    var exportStartDate by remember { mutableStateOf(today.minusDays(29)) }
    var exportEndDate by remember { mutableStateOf(today) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val providerAvailable = healthConnect.access.providerAvailable
    val operationIdle = healthConnect.fileOperation == HealthConnectFileOperation.IDLE

    LaunchedEffect(healthConnect.exportPreparation) {
        healthConnect.exportPreparation?.let { preparation ->
            exportStartDate = preparation.range.startDate
            exportEndDate = preparation.range.endDate
            selectedPackages = preparation.packages.mapTo(linkedSetOf()) { it.packageName }
        }
    }

    fun prepareExport(start: LocalDate, end: LocalDate) {
        exportStartDate = start
        exportEndDate = end
        selectedPackages = emptySet()
        healthConnect.onPrepareSleepExport(SleepExportRange(start, end, zoneId))
    }

    SettingsSection("Import & export") {
        OutlinedButton(
            onClick = {
                showExportDialog = true
                val days = when (val range = healthConnect.dataRange) {
                    HealthDataRange.DefaultPeriod -> 30
                    is HealthDataRange.Custom -> range.days
                    HealthDataRange.EntireHistory -> null
                }
                prepareExport(days?.let { today.minusDays(it.toLong() - 1) } ?: LocalDate.ofEpochDay(0), today)
            },
            enabled = providerAvailable && operationIdle,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_sleep_records"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Export sleep records")
        }
        if (!healthConnect.fileWriteSupported) {
            Text(
                "File import requires Android 14 or later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("sleep_file_import_unsupported"),
            )
        } else {
            Text(
                "Imported records in selected range: ${healthConnect.fileImportedRecordCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("imported_sleep_record_count"),
            )
            OutlinedButton(
                onClick = healthConnect.onImportSleepFiles,
                enabled = providerAvailable && operationIdle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import_sleep_files"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Import sleep files")
            }
            if (healthConnect.fileDeletionSupported) {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = providerAvailable && operationIdle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delete_imported_records"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete imported records")
                }
            } else {
                Text(
                    "Debug import mode: files are upserted directly without checking existing " +
                        "Health Connect records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("legacy_debug_sleep_import_note"),
                )
            }
            when (healthConnect.fileOperation) {
                HealthConnectFileOperation.PREPARING_EXPORT -> FileOperationProgress("Preparing sleep export…")
                HealthConnectFileOperation.EXPORTING -> FileOperationProgress("Exporting sleep records…")
                HealthConnectFileOperation.IMPORTING -> FileOperationProgress("Importing sleep files…")
                HealthConnectFileOperation.DELETING -> FileOperationProgress("Deleting imported records…")
                HealthConnectFileOperation.IDLE -> Unit
            }
            healthConnect.fileOperationMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sleep_file_operation_message"),
                )
            }
            healthConnect.fileOperationError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("sleep_file_operation_error"),
                )
            }
            healthConnect.fileImportResult?.issues?.firstOrNull()?.let { issue ->
                Text(
                    issue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!healthConnect.fileWriteSupported) {
            when (healthConnect.fileOperation) {
                HealthConnectFileOperation.PREPARING_EXPORT -> FileOperationProgress("Preparing sleep export…")
                HealthConnectFileOperation.EXPORTING -> FileOperationProgress("Exporting sleep records…")
                else -> Unit
            }
            healthConnect.fileOperationMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            healthConnect.fileOperationError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showExportDialog) {
        SleepExportDialog(
            startDate = exportStartDate,
            endDate = exportEndDate,
            customDays = customDays,
            preparation = healthConnect.exportPreparation,
            isPreparing = healthConnect.fileOperation == HealthConnectFileOperation.PREPARING_EXPORT,
            selectedPackages = selectedPackages,
            onSelectedPackagesChange = { selectedPackages = it },
            onRangeChange = ::prepareExport,
            onDismiss = {
                showExportDialog = false
                healthConnect.onCancelSleepExport()
            },
            onExport = {
                showExportDialog = false
                healthConnect.onCreateSleepExportDocument(selectedPackages)
            },
        )
    }

    if (showDeleteConfirmation && healthConnect.fileDeletionSupported) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete imported records?") },
            text = {
                Text(
                    "This permanently deletes every sleep record imported by this Dark Hour app " +
                        "from Health Connect. Records owned by other apps are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        healthConnect.onDeleteOwnedSleepRecords()
                    },
                    modifier = Modifier.testTag("confirm_delete_imported_records"),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false },
                    modifier = Modifier.testTag("cancel_delete_imported_records"),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepExportDialog(
    startDate: LocalDate,
    endDate: LocalDate,
    customDays: Int,
    preparation: one.aozora.darkhour.data.SleepExportPreparation?,
    isPreparing: Boolean,
    selectedPackages: Set<String>,
    onSelectedPackagesChange: (Set<String>) -> Unit,
    onRangeChange: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    var pickingStart by remember { mutableStateOf<Boolean?>(null) }
    val today = remember { LocalDate.now() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export sleep records") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportDateField("Start date", startDate, "export_start_date") { pickingStart = true }
                ExportDateField("End date", endDate, "export_end_date") { pickingStart = false }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onRangeChange(today.minusDays(29), today) }) {
                        Text("30 days")
                    }
                    TextButton(onClick = {
                        onRangeChange(today.minusDays(customDays.coerceAtLeast(1).toLong() - 1), today)
                    }) {
                        Text("$customDays days")
                    }
                    TextButton(onClick = { onRangeChange(LocalDate.ofEpochDay(0), today) }) {
                        Text("All history")
                    }
                }
                when {
                    isPreparing -> FileOperationProgress("Finding source packages…")
                    preparation == null -> Text("Choose a date range to find records.")
                    preparation.packages.isEmpty() -> Text("No sleep records in this date range.")
                    preparation.packages.size == 1 -> {
                        val source = preparation.packages.single()
                        Text("${source.displayName} (${source.packageName}) · ${source.recordCount}")
                    }
                    else -> preparation.packages.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedPackagesChange(
                                        if (source.packageName in selectedPackages) {
                                            selectedPackages - source.packageName
                                        } else {
                                            selectedPackages + source.packageName
                                        },
                                    )
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = source.packageName in selectedPackages,
                                onCheckedChange = null,
                            )
                            Column {
                                Text(source.displayName)
                                Text(
                                    "${source.packageName} · ${source.recordCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onExport,
                enabled = !isPreparing && preparation != null && selectedPackages.isNotEmpty(),
                modifier = Modifier.testTag("confirm_sleep_export"),
            ) { Text("Create file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    pickingStart?.let { isStart ->
        val selected = if (isStart) startDate else endDate
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = null },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickerState.selectedDateMillis?.let { millis ->
                        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    if (date != null) {
                        if (isStart && !date.isAfter(endDate)) onRangeChange(date, endDate)
                        if (!isStart && !date.isBefore(startDate)) onRangeChange(startDate, date)
                    }
                    pickingStart = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingStart = null }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun ExportDateField(
    label: String,
    date: LocalDate,
    testTag: String,
    onClick: () -> Unit,
) {
    OutlinedTextField(
        value = date.toString(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun FileOperationProgress(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PrivacySettingsSection(
    uriHandler: UriHandler,
) {
    SettingsSection("Privacy") {
        OutlinedButton(
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy_policy_button"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Open privacy policy")
        }
        Text(
            "Updated July 15, 2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun settingsImportStatusText(
    healthConnect: HealthConnectState,
    visibleRecordCount: Int,
): String =
    when {
        healthConnect.importError != null -> healthConnect.importError
        healthConnect.access == HealthConnectAccess.PERMISSION_REQUIRED ->
            "Permission required"
        healthConnect.access == HealthConnectAccess.SETUP_REQUIRED ->
            "Health Connect setup required"
        healthConnect.access == HealthConnectAccess.UPDATE_REQUIRED ->
            "Provider update required"
        healthConnect.access == HealthConnectAccess.INSTALL_REQUIRED ->
            "Health Connect is not installed"
        healthConnect.access == HealthConnectAccess.UNAVAILABLE ->
            "Unavailable on this device"
        healthConnect.isRefreshing -> importStatusText(
            importPhase = healthConnect.importPhase,
            importedRecordCount = healthConnect.importedRecordCount,
            expectedRecordCount = healthConnect.expectedRecordCount,
            isImportPartial = healthConnect.isImportPartial,
            visibleRecordCount = visibleRecordCount,
        )
        else -> "$visibleRecordCount sleep records imported"
    }
