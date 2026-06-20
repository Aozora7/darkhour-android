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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    totalHistoryDays: Int? = null,
    onRequestHistoryPermission: () -> Unit = {},
    onHealthDataRangeChange: (HealthDataRange) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val customDays = (healthDataRange as? HealthDataRange.Custom)?.days
        ?: HealthDataRange.DEFAULT_CUSTOM_DAYS
    val maxCustomDays = maxOf(
        HealthDataRange.MINIMUM_CUSTOM_DAYS,
        totalHistoryDays ?: customDays,
    )
    var pendingCustomDays by remember(healthDataRange, maxCustomDays) {
        mutableFloatStateOf(customDays.coerceIn(HealthDataRange.MINIMUM_CUSTOM_DAYS, maxCustomDays).toFloat())
    }

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
                HealthDataRangeOptions.forEachIndexed { index, option ->
                    val selected = option.isSelected(healthDataRange)
                    SegmentedButton(
                        selected = selected,
                        onClick = {
                            onHealthDataRangeChange(
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
                        onValueChange = { pendingCustomDays = it },
                        onValueChangeFinished = {
                            onHealthDataRangeChange(
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
                "Updated June 18, 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    }
}

private const val PRIVACY_POLICY_URL =
    "https://github.com/Aozora7/darkhour-android/blob/master/PRIVACY.md"

private enum class HealthDataRangeOption(
    val label: String,
    val testTag: String,
) {
    DEFAULT("Last 30 days", "health_range_default"),
    CUSTOM("Custom", "health_range_custom"),
    HISTORY("All history", "health_range_history");

    fun isSelected(range: HealthDataRange): Boolean = when (this) {
        DEFAULT -> range == HealthDataRange.DEFAULT_PERIOD
        CUSTOM -> range is HealthDataRange.Custom
        HISTORY -> range == HealthDataRange.ENTIRE_HISTORY
    }

    fun toRange(customDays: Int): HealthDataRange = when (this) {
        DEFAULT -> HealthDataRange.DEFAULT_PERIOD
        CUSTOM -> HealthDataRange.custom(customDays)
        HISTORY -> HealthDataRange.ENTIRE_HISTORY
    }
}

private val HealthDataRangeOptions = HealthDataRangeOption.entries

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
