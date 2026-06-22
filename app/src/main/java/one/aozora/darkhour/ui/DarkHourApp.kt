package one.aozora.darkhour.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material.icons.automirrored.outlined.EventNote
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase
import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.buildPeriodogramAnchors
import one.aozora.darkhour.core.periodogram.computePeriodogram
import one.aozora.darkhour.ui.actogram.ActogramLayoutEngine
import one.aozora.darkhour.ui.actogram.ActogramScreen
import one.aozora.darkhour.ui.schedule.ScheduleScreen
import one.aozora.darkhour.ui.settings.SettingsScreen
import one.aozora.darkhour.ui.stats.StatsScreen
import kotlin.time.Duration.Companion.milliseconds

private data class DestinationItem(
    val destination: DarkHourDestination,
    val icon: ImageVector,
)

private val DestinationItems = listOf(
    DestinationItem(DarkHourDestination.ACTOGRAM, Icons.Outlined.Bedtime),
    DestinationItem(DarkHourDestination.STATS, Icons.Outlined.QueryStats),
    DestinationItem(DarkHourDestination.SCHEDULE, Icons.AutoMirrored.Outlined.EventNote),
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
    initialScheduleEntries: List<ScheduleEntry> = emptyList(),
    onScheduleEntriesChange: (List<ScheduleEntry>) -> Unit = {},
    healthConnectAccess: HealthConnectAccess = HealthConnectAccess.CONNECTED,
    healthDataRange: HealthDataRange = HealthDataRange.DEFAULT_PERIOD,
    hasHistoryPermission: Boolean = true,
    isRefreshing: Boolean = false,
    importedRecordCount: Int = 0,
    expectedRecordCount: Int? = null,
    isImportPartial: Boolean = false,
    importPhase: HealthImportPhase = HealthImportPhase.IDLE,
    importError: String? = null,
    totalHistoryDays: Int? = null,
    onRequestHealthPermissions: () -> Unit = {},
    onRequestHistoryPermission: () -> Unit = {},
    onHealthDataRangeChange: (HealthDataRange) -> Unit = {},
) {
    var actogramTransforming by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(initialDisplayOptions) }
    var settings by remember { mutableStateOf(initialSettings) }
    var scheduleEntries by remember { mutableStateOf(initialScheduleEntries) }
    var pendingScheduleEditId by remember { mutableStateOf<Long?>(null) }
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
    val layout = remember(filteredRecords, analysis.days, scheduleEntries, rowHours) {
        ActogramLayoutEngine.build(
            records = filteredRecords,
            circadianDays = analysis.days,
            scheduleEntries = scheduleEntries,
            rowHours = rowHours,
        )
    }
    LaunchedEffect(options) {
        delay(300.milliseconds)
        onDisplayOptionsChange(options)
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { DestinationItems.size })
    val scope = rememberCoroutineScope()

    fun selectDestination(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        onAppSettingsChange(updated)
    }

    fun updateDisplayOptions(updated: ActogramDisplayOptions) {
        options = updated
    }

    fun updateScheduleEntries(updated: List<ScheduleEntry>) {
        scheduleEntries = updated
        onScheduleEntriesChange(updated)
    }

    fun editScheduleEntry(entryId: Long) {
        pendingScheduleEditId = entryId
        val scheduleIndex = DestinationItems.indexOfFirst {
            it.destination == DarkHourDestination.SCHEDULE
        }
        if (scheduleIndex >= 0) selectDestination(scheduleIndex)
    }

    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    AppNavigationRail(
                        selectedIndex = pagerState.currentPage,
                        onSelected = ::selectDestination,
                    )
                    AppPager(
                        pagerState = pagerState,
                        records = filteredRecords,
                        analysis = analysis,
                        periodogram = periodogram,
                        layout = layout,
                        options = options,
                        settings = settings,
                        scheduleEntries = scheduleEntries,
                        onOptionsChange = ::updateDisplayOptions,
                        onTransformingChange = { actogramTransforming = it },
                        onSettingsChange = ::updateSettings,
                        onScheduleEntriesChange = ::updateScheduleEntries,
                        pendingScheduleEditId = pendingScheduleEditId,
                        onScheduleEditConsumed = { pendingScheduleEditId = null },
                        onEditScheduleEntry = ::editScheduleEntry,
                        healthConnectAccess = healthConnectAccess,
                        healthDataRange = healthDataRange,
                        hasHistoryPermission = hasHistoryPermission,
                        isRefreshing = isRefreshing,
                        importedRecordCount = importedRecordCount,
                        expectedRecordCount = expectedRecordCount,
                        isImportPartial = isImportPartial,
                        importPhase = importPhase,
                        importError = importError,
                        totalHistoryDays = totalHistoryDays,
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    bottomBar = {
                        AppNavigationBar(
                            selectedIndex = pagerState.currentPage,
                            pagerState = pagerState,
                            onSelected = ::selectDestination,
                        )
                    },
                ) { padding ->
                    AppPager(
                        pagerState = pagerState,
                        records = filteredRecords,
                        analysis = analysis,
                        periodogram = periodogram,
                        layout = layout,
                        options = options,
                        settings = settings,
                        scheduleEntries = scheduleEntries,
                        onOptionsChange = ::updateDisplayOptions,
                        onTransformingChange = { actogramTransforming = it },
                        onSettingsChange = ::updateSettings,
                        onScheduleEntriesChange = ::updateScheduleEntries,
                        pendingScheduleEditId = pendingScheduleEditId,
                        onScheduleEditConsumed = { pendingScheduleEditId = null },
                        onEditScheduleEntry = ::editScheduleEntry,
                        healthConnectAccess = healthConnectAccess,
                        healthDataRange = healthDataRange,
                        hasHistoryPermission = hasHistoryPermission,
                        isRefreshing = isRefreshing,
                        importedRecordCount = importedRecordCount,
                        expectedRecordCount = expectedRecordCount,
                        isImportPartial = isImportPartial,
                        importPhase = importPhase,
                        importError = importError,
                        totalHistoryDays = totalHistoryDays,
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
    settings: AppSettings,
    scheduleEntries: List<ScheduleEntry>,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onScheduleEntriesChange: (List<ScheduleEntry>) -> Unit,
    pendingScheduleEditId: Long?,
    onScheduleEditConsumed: () -> Unit,
    onEditScheduleEntry: (Long) -> Unit,
    healthConnectAccess: HealthConnectAccess,
    healthDataRange: HealthDataRange,
    hasHistoryPermission: Boolean,
    isRefreshing: Boolean,
    importedRecordCount: Int,
    expectedRecordCount: Int?,
    isImportPartial: Boolean,
    importPhase: HealthImportPhase,
    importError: String?,
    totalHistoryDays: Int?,
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
                        onOptionsChange = onOptionsChange,
                        onTransformingChange = onTransformingChange,
                        onEditScheduleEntry = onEditScheduleEntry,
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
            DarkHourDestination.SCHEDULE -> ScheduleScreen(
                entries = scheduleEntries,
                onEntriesChange = onScheduleEntriesChange,
                editEntryId = pendingScheduleEditId,
                onEditEntryConsumed = onScheduleEditConsumed,
            )
            DarkHourDestination.SETTINGS -> SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                healthConnectAccess = healthConnectAccess,
                healthDataRange = healthDataRange,
                hasHistoryPermission = hasHistoryPermission,
                isRefreshing = isRefreshing,
                importedRecordCount = importedRecordCount,
                expectedRecordCount = expectedRecordCount,
                isImportPartial = isImportPartial,
                importPhase = importPhase,
                importError = importError,
                recordCount = records.size,
                totalHistoryDays = totalHistoryDays,
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
        Column(
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
                        dataRange.requiresHistoryPermission
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
    pagerState: androidx.compose.foundation.pager.PagerState,
    onSelected: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .selectableGroup()
            .testTag("bottom_navigation"),
    ) {
        val itemWidth = maxWidth / DestinationItems.size
        val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
        val textMeasurer = rememberTextMeasurer()
        val horizontalContentWidth = with(LocalDensity.current) { (20.dp + 8.dp).toPx() }
        val useStackedItems = DestinationItems.any { item ->
            val labelWidth = textMeasurer.measure(
                text = item.destination.label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            ).size.width
            labelWidth + horizontalContentWidth > itemWidthPx
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(if (useStackedItems) 64.dp else 48.dp)
        ) {
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
                    if (useStackedItems) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .alpha(if (selected) 1f else 0.7f),
                        ) {
                            AppNavigationIcon(item = item, selected = selected)
                            Spacer(Modifier.height(2.dp))
                            AppNavigationLabel(item = item, selected = selected)
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.alpha(if (selected) 1f else 0.7f)
                        ) {
                            AppNavigationIcon(item = item, selected = selected)
                            Spacer(Modifier.width(8.dp))
                            AppNavigationLabel(item = item, selected = selected)
                        }
                    }
                }
            }
        }

        // Sliding underline indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val pagerPosition =
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    IntOffset(
                        x = (itemWidthPx * pagerPosition.coerceIn(
                            0f,
                            DestinationItems.lastIndex.toFloat(),
                        )).roundToInt(),
                        y = 0,
                    )
                }
                .width(itemWidth)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun AppNavigationIcon(
    item: DestinationItem,
    selected: Boolean,
) {
    Icon(
        imageVector = item.icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun AppNavigationLabel(
    item: DestinationItem,
    selected: Boolean,
) {
    Text(
        text = item.destination.label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
    )
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
