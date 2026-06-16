package one.aozora.darkhour.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.AppSettings
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    healthConnectAccess: HealthConnectAccess = HealthConnectAccess.CONNECTED,
    healthDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    hasHistoryPermission: Boolean = true,
    isRefreshing: Boolean = false,
    importError: String? = null,
    recordCount: Int = 0,
    onRequestHistoryPermission: () -> Unit = {},
    onHealthDataRangeChange: (HealthDataRange) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings", 
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

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

        SettingsSection("Data") {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                HealthDataRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = healthDataRange == range,
                        onClick = { onHealthDataRangeChange(range) },
                        shape = SegmentedButtonDefaults.itemShape(index, HealthDataRange.entries.size),
                        modifier = Modifier.testTag(
                            if (range == HealthDataRange.DEFAULT_PERIOD) {
                                "health_range_default"
                            } else {
                                "health_range_history"
                            },
                        ),
                    ) {
                        Text(
                            if (range == HealthDataRange.DEFAULT_PERIOD) "Last 30 days" else "All history",
                        )
                    }
                }
            }
            Text(
                when {
                    importError != null -> importError
                    healthConnectAccess == HealthConnectAccess.PERMISSION_REQUIRED ->
                        "Permission required"
                    healthConnectAccess == HealthConnectAccess.UPDATE_REQUIRED ->
                        "Provider update required"
                    healthConnectAccess == HealthConnectAccess.UNAVAILABLE ->
                        "Unavailable on this device"
                    isRefreshing -> "Refreshing sleep data..."
                    else -> "$recordCount sleep records imported"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (
                !hasHistoryPermission &&
                healthConnectAccess != HealthConnectAccess.UNAVAILABLE &&
                healthConnectAccess != HealthConnectAccess.UPDATE_REQUIRED
            ) {
                OutlinedButton(
                    onClick = onRequestHistoryPermission,
                    modifier = Modifier.testTag("request_history_permission"),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Allow history access")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag == null) Modifier else Modifier.testTag(testTag),
        )
    }
}
