package one.aozora.darkhour.ui.actogram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.BuildConfig
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.LocalActogramDisplay
import one.aozora.darkhour.ui.LocalAppSettings
import one.aozora.darkhour.ui.LocalHealthConnectState
import one.aozora.darkhour.ui.LocalScheduleState
import one.aozora.darkhour.ui.LocalDeveloperCircadian
import one.aozora.darkhour.ui.theme.CircadianForecast
import one.aozora.darkhour.ui.theme.SurfaceDark

@Composable
fun ActogramScreen(
    onTransformingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actogramDisplay = LocalActogramDisplay.current
    val (layout, options, onOptionsChange) = actogramDisplay
    val (settings, onSettingsChange) = LocalAppSettings.current
    val schedule = LocalScheduleState.current
    val healthConnect = LocalHealthConnectState.current
    val developerCircadian = LocalDeveloperCircadian.current

    if (healthConnect.access != HealthConnectAccess.CONNECTED) {
        HealthConnectGate(
            access = healthConnect.access,
            dataRangeRequiresHistoryPermission = healthConnect.dataRange.requiresHistoryPermission,
            onRequestPermissions = healthConnect.onRequestHealthPermissions,
            onInstallHealthConnect = healthConnect.onInstallHealthConnect,
            onOpenHealthConnect = healthConnect.onOpenHealthConnect,
            modifier = modifier,
        )
        return
    }

    val useIsoDateTime = settings.useIsoDateTime
    var showOptions by remember { mutableStateOf(false) }
    var showDeveloperTools by remember { mutableStateOf(false) }
    var selection by remember(layout) { mutableStateOf<ActogramSelection?>(null) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ActogramCanvas(
                layout = layout,
                options = options,
                useIsoDateTime = useIsoDateTime,
                selection = selection,
                onSelectionChange = { selection = it },
                onRowHeightChange = { onOptionsChange(options.copy(rowHeightDp = it)) },
                onTransformingChange = onTransformingChange,
                modifier = Modifier.fillMaxSize().testTag("actogram_canvas"),
            )
            if (!healthConnect.hasHistoryPermission && !settings.historyAccessCalloutDismissed) {
                HistoryAccessCallout(
                    dataRange = healthConnect.dataRange,
                    onAllowHistory = {
                        if (healthConnect.dataRange == HealthDataRange.ENTIRE_HISTORY) {
                            healthConnect.onRequestHistoryPermission()
                        } else {
                            healthConnect.onDataRangeChange(HealthDataRange.ENTIRE_HISTORY)
                        }
                    },
                    onDismiss = {
                        onSettingsChange(
                            settings.copy(historyAccessCalloutDismissed = true),
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 36.dp)
                        .testTag("actogram_history_callout"),
                )
            }
            FloatingActionButton(
                onClick = { showOptions = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("actogram_options"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "Visualization options")
            }
            if (BuildConfig.DEBUG) {
                FloatingActionButton(
                    onClick = { showDeveloperTools = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 84.dp)
                        .testTag("circadian_developer_tools"),
                    containerColor = CircadianForecast,
                    contentColor = SurfaceDark,
                ) {
                    Icon(Icons.Outlined.Build, contentDescription = "Circadian developer tools")
                }
            }
        }
        AnimatedVisibility(
            visible = selection != null,
            enter = slideInVertically(initialOffsetY = { it }) +
                expandVertically(expandFrom = Alignment.Bottom) +
                fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) +
                shrinkVertically(shrinkTowards = Alignment.Bottom) +
                fadeOut(),
        ) {
            selection?.let {
                ActogramDetailsPanel(
                    selection = it,
                    sleepMetadata = (it as? ActogramSelection.Sleep)?.let { sleep ->
                        actogramDisplay.recordMetadata[sleep.logId]
                    },
                    useIsoDateTime = useIsoDateTime,
                    onEditScheduleEntry = { entryId ->
                        selection = null
                        schedule.onEditEntry(entryId)
                    },
                    onDismiss = { selection = null },
                )
            }
        }
    }

    if (showOptions) {
        ActogramOptionsSheet(
            options = options,
            onOptionsChange = onOptionsChange,
            onDismiss = { showOptions = false },
        )
    }
    if (BuildConfig.DEBUG && showDeveloperTools) {
        CircadianDeveloperSheet(
            state = developerCircadian,
            onDismiss = { showDeveloperTools = false },
        )
    }
}
