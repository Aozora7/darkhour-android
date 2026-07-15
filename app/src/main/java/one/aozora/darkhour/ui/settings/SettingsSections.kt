package one.aozora.darkhour.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthConnectFileOperation
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.HealthConnectState
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
            healthConnect.access != HealthConnectAccess.UNAVAILABLE &&
            healthConnect.access != HealthConnectAccess.UPDATE_REQUIRED
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

@Composable
internal fun ImportSettingsSection(
    healthConnect: HealthConnectState,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    SettingsSection("Import") {
        if (!healthConnect.fileWriteSupported) {
            Text(
                "File import requires Android 14 or later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("sleep_file_import_unsupported"),
            )
        } else {
            val providerAvailable = healthConnect.access != HealthConnectAccess.UNAVAILABLE &&
                healthConnect.access != HealthConnectAccess.UPDATE_REQUIRED
            val operationIdle = healthConnect.fileOperation == HealthConnectFileOperation.IDLE
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
            when (healthConnect.fileOperation) {
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
    }

    if (showDeleteConfirmation) {
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
        healthConnect.access == HealthConnectAccess.UPDATE_REQUIRED ->
            "Provider update required"
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
