package one.aozora.darkhour.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.LocalAppSettings
import one.aozora.darkhour.ui.LocalHealthConnectState
import one.aozora.darkhour.ui.LocalSleepAnalysis

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val (settings, onSettingsChange) = LocalAppSettings.current
    val healthConnect = LocalHealthConnectState.current
    val healthDataRange = healthConnect.dataRange
    val recordCount = LocalSleepAnalysis.current.records.size
    val uriHandler = LocalUriHandler.current
    val customDays = (healthDataRange as? HealthDataRange.Custom)?.days
        ?: HealthDataRange.DEFAULT_CUSTOM_DAYS
    val maxCustomDays = maxOf(
        HealthDataRange.MINIMUM_CUSTOM_DAYS,
        healthConnect.totalHistoryDays ?: healthConnect.availableHistoryDays,
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
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )

        AnalysisSettingsSection(
            settings = settings,
            onSettingsChange = onSettingsChange,
        )
        AppearanceSettingsSection(
            settings = settings,
            onSettingsChange = onSettingsChange,
        )
        DataSettingsSection(
            healthConnect = healthConnect,
            healthDataRange = healthDataRange,
            pendingCustomDays = pendingCustomDays,
            maxCustomDays = maxCustomDays,
            visibleRecordCount = recordCount,
            onPendingCustomDaysChange = { pendingCustomDays = it },
        )
        ImportExportSettingsSection(
            healthConnect = healthConnect,
            customDays = pendingCustomDays.toInt(),
        )
        FeedbackSettingsSection(uriHandler = uriHandler)
        PrivacySettingsSection(uriHandler = uriHandler)
    }
}
