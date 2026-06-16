package one.aozora.darkhour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.ui.actogram.ActogramLayoutEngine
import one.aozora.darkhour.ui.actogram.ActogramScreen
import one.aozora.darkhour.ui.settings.SettingsScreen
import one.aozora.darkhour.ui.stats.StatsScreen

private data class DestinationItem(
    val destination: DarkHourDestination,
    val icon: ImageVector,
)

private val DestinationItems = listOf(
    DestinationItem(DarkHourDestination.ACTOGRAM, Icons.Outlined.Bedtime),
    DestinationItem(DarkHourDestination.STATS, Icons.Outlined.QueryStats),
    DestinationItem(DarkHourDestination.SETTINGS, Icons.Outlined.Settings),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DarkHourApp(
    records: List<SleepRecord>,
    modifier: Modifier = Modifier,
    initialSettings: AppSettings = AppSettings(),
    onAppSettingsChange: (AppSettings) -> Unit = {},
    initialDisplayOptions: ActogramDisplayOptions = ActogramDisplayOptions(),
    onDisplayOptionsChange: (ActogramDisplayOptions) -> Unit = {},
    healthConnectAccess: HealthConnectAccess = HealthConnectAccess.CONNECTED,
    healthDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    hasHistoryPermission: Boolean = true,
    isRefreshing: Boolean = false,
    importError: String? = null,
    onRequestHealthPermissions: () -> Unit = {},
    onRequestHistoryPermission: () -> Unit = {},
    onHealthDataRangeChange: (HealthDataRange) -> Unit = {},
) {
    var immersive by rememberSaveable { mutableStateOf(false) }
    var actogramTransforming by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(initialDisplayOptions) }
    var settings by remember { mutableStateOf(initialSettings) }
    val filteredRecords = remember(records, settings.includeNaps) {
        if (settings.includeNaps) records else records.filter { it.isMainSleep }
    }
    val analysis = remember(filteredRecords, settings.forecastDays) {
        CircadianAnalyzer.analyze(filteredRecords, extraDays = settings.forecastDays)
    }
    val periodogram = remember(filteredRecords) {
        computePeriodogram(buildPeriodogramAnchors(filteredRecords))
    }
    val rowHours = when (options.timeScale) {
        ActogramTimeScale.HOURS_24 -> 24.0
        ActogramTimeScale.CIRCADIAN_TAU -> analysis.globalTau
        ActogramTimeScale.CUSTOM -> options.customHours.toDouble()
    }
    val layout = remember(filteredRecords, analysis.days, rowHours) {
        ActogramLayoutEngine.build(
            records = filteredRecords,
            circadianDays = analysis.days,
            rowHours = rowHours,
        )
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { DestinationItems.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != DarkHourDestination.ACTOGRAM.ordinal) {
            immersive = false
        }
    }

    fun selectDestination(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        onAppSettingsChange(updated)
    }

    fun updateDisplayOptions(updated: ActogramDisplayOptions) {
        options = updated
        onDisplayOptionsChange(updated)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val hideNavigation = immersive && pagerState.currentPage == DarkHourDestination.ACTOGRAM.ordinal

        BackHandler(enabled = hideNavigation) {
            immersive = false
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                if (!hideNavigation) {
                    AppNavigationRail(
                        selectedIndex = pagerState.currentPage,
                        onSelected = ::selectDestination,
                    )
                }
                AppPager(
                    pagerState = pagerState,
                    records = filteredRecords,
                    analysis = analysis,
                    periodogram = periodogram,
                    layout = layout,
                    options = options,
                    immersive = immersive,
                    settings = settings,
                    onOptionsChange = ::updateDisplayOptions,
                    onImmersiveChange = { immersive = it },
                    onTransformingChange = { actogramTransforming = it },
                    onSettingsChange = ::updateSettings,
                    healthConnectAccess = healthConnectAccess,
                    healthDataRange = healthDataRange,
                    hasHistoryPermission = hasHistoryPermission,
                    isRefreshing = isRefreshing,
                    importError = importError,
                    onRequestHealthPermissions = onRequestHealthPermissions,
                    onRequestHistoryPermission = onRequestHistoryPermission,
                    onHealthDataRangeChange = onHealthDataRangeChange,
                    userScrollEnabled = !actogramTransforming,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    if (!hideNavigation) {
                        AppNavigationBar(
                            selectedIndex = pagerState.currentPage,
                            pagerPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                            onSelected = ::selectDestination,
                        )
                    }
                },
            ) { padding ->
                AppPager(
                    pagerState = pagerState,
                    records = filteredRecords,
                    analysis = analysis,
                    periodogram = periodogram,
                    layout = layout,
                    options = options,
                    immersive = immersive,
                    settings = settings,
                    onOptionsChange = ::updateDisplayOptions,
                    onImmersiveChange = { immersive = it },
                    onTransformingChange = { actogramTransforming = it },
                    onSettingsChange = ::updateSettings,
                    healthConnectAccess = healthConnectAccess,
                    healthDataRange = healthDataRange,
                    hasHistoryPermission = hasHistoryPermission,
                    isRefreshing = isRefreshing,
                    importError = importError,
                    onRequestHealthPermissions = onRequestHealthPermissions,
                    onRequestHistoryPermission = onRequestHistoryPermission,
                    onHealthDataRangeChange = onHealthDataRangeChange,
                    userScrollEnabled = !actogramTransforming,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppPager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    records: List<SleepRecord>,
    analysis: one.aozora.darkhour.core.circadian.csf.CsfAnalysis,
    periodogram: one.aozora.darkhour.core.periodogram.PeriodogramResult,
    layout: one.aozora.darkhour.ui.actogram.ActogramLayout,
    options: ActogramDisplayOptions,
    immersive: Boolean,
    settings: AppSettings,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
    onImmersiveChange: (Boolean) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    healthConnectAccess: HealthConnectAccess,
    healthDataRange: HealthDataRange,
    hasHistoryPermission: Boolean,
    isRefreshing: Boolean,
    importError: String?,
    onRequestHealthPermissions: () -> Unit,
    onRequestHistoryPermission: () -> Unit,
    onHealthDataRangeChange: (HealthDataRange) -> Unit,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag("main_pager"),
        userScrollEnabled = userScrollEnabled,
        beyondViewportPageCount = 1,
    ) { page ->
        when (DarkHourDestination.entries[page]) {
            DarkHourDestination.ACTOGRAM -> {
                if (healthConnectAccess == HealthConnectAccess.CONNECTED) {
                    ActogramScreen(
                        layout = layout,
                        options = options,
                        useIsoDateTime = settings.useIsoDateTime,
                        immersive = immersive,
                        onOptionsChange = onOptionsChange,
                        onImmersiveChange = onImmersiveChange,
                        onTransformingChange = onTransformingChange,
                    )
                } else {
                    HealthConnectGate(
                        access = healthConnectAccess,
                        dataRange = healthDataRange,
                        onRequestPermissions = onRequestHealthPermissions,
                    )
                }
            }
            DarkHourDestination.STATS -> StatsScreen(records, analysis, periodogram)
            DarkHourDestination.SETTINGS -> SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                healthConnectAccess = healthConnectAccess,
                healthDataRange = healthDataRange,
                hasHistoryPermission = hasHistoryPermission,
                isRefreshing = isRefreshing,
                importError = importError,
                recordCount = records.size,
                onRequestHistoryPermission = onRequestHistoryPermission,
                onHealthDataRangeChange = onHealthDataRangeChange,
            )
        }
    }
}

@Composable
private fun HealthConnectGate(
    access: HealthConnectAccess,
    dataRange: HealthDataRange,
    onRequestPermissions: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("health_connect_gate"),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when (access) {
                    HealthConnectAccess.PERMISSION_REQUIRED -> "Connect Health Connect"
                    HealthConnectAccess.UPDATE_REQUIRED -> "Update Health Connect"
                    HealthConnectAccess.UNAVAILABLE -> "Health Connect unavailable"
                    HealthConnectAccess.CONNECTED -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (access) {
                    HealthConnectAccess.PERMISSION_REQUIRED -> if (
                        dataRange == HealthDataRange.ENTIRE_HISTORY
                    ) {
                        "Allow sleep and history access to show your complete actogram."
                    } else {
                        "Allow sleep access to show your last 30 days in the actogram."
                    }
                    HealthConnectAccess.UPDATE_REQUIRED ->
                        "Install the available Health Connect update, then return to Dark Hour."
                    HealthConnectAccess.UNAVAILABLE ->
                        "This device does not provide Health Connect."
                    HealthConnectAccess.CONNECTED -> ""
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (access == HealthConnectAccess.PERMISSION_REQUIRED) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.testTag("request_health_permissions"),
                ) {
                    Text("Allow access")
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    selectedIndex: Int,
    pagerPosition: Float,
    onSelected: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .height(48.dp)
            .selectableGroup()
            .testTag("bottom_navigation"),
    ) {
        Row(Modifier.fillMaxSize()) {
            DestinationItems.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            onClick = { onSelected(index) },
                            role = Role.Tab,
                        )
                        .testTag("destination_${item.destination.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.alpha(if (selected) 1f else 0.58f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.destination.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
        val itemWidth = maxWidth / DestinationItems.size
        Spacer(
            modifier = Modifier
                .offset(
                    x = itemWidth * pagerPosition.coerceIn(
                        minimumValue = 0f,
                        maximumValue = DestinationItems.lastIndex.toFloat(),
                    ) + (itemWidth - 40.dp) / 2,
                )
                .width(40.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun AppNavigationRail(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    NavigationRail(modifier = Modifier.testTag("navigation_rail")) {
        DestinationItems.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.destination.label) },
                modifier = Modifier.testTag("destination_${item.destination.name.lowercase()}"),
            )
        }
    }
}
