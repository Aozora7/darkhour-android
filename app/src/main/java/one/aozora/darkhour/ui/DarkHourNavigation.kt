package one.aozora.darkhour.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import one.aozora.darkhour.ui.actogram.ActogramScreen
import one.aozora.darkhour.ui.schedule.ScheduleScreen
import one.aozora.darkhour.ui.settings.SettingsScreen
import one.aozora.darkhour.ui.stats.StatsScreen

internal data class DestinationItem(
    val destination: DarkHourDestination,
    val icon: ImageVector,
)

internal val DestinationItems = listOf(
    DestinationItem(DarkHourDestination.ACTOGRAM, Icons.Outlined.Bedtime),
    DestinationItem(DarkHourDestination.STATS, Icons.Outlined.QueryStats),
    DestinationItem(DarkHourDestination.SCHEDULE, Icons.AutoMirrored.Outlined.EventNote),
    DestinationItem(DarkHourDestination.SETTINGS, Icons.Outlined.Settings),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppPager(
    pagerState: PagerState,
    onTransformingChange: (Boolean) -> Unit,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxSize()
            .testTag("main_pager"),
        userScrollEnabled = userScrollEnabled,
        beyondViewportPageCount = 1,
    ) { page ->
        when (DarkHourDestination.entries[page]) {
            DarkHourDestination.ACTOGRAM -> ActogramScreen(
                onTransformingChange = onTransformingChange,
            )
            DarkHourDestination.STATS -> StatsScreen()
            DarkHourDestination.SCHEDULE -> ScheduleScreen()
            DarkHourDestination.SETTINGS -> SettingsScreen()
        }
    }
}
