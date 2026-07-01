package one.aozora.darkhour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun AppNavigationBar(
    selectedIndex: Int,
    pagerState: PagerState,
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
                .height(if (useStackedItems) 64.dp else 48.dp),
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
                            modifier = Modifier.alpha(if (selected) 1f else 0.7f),
                        ) {
                            AppNavigationIcon(item = item, selected = selected)
                            Spacer(Modifier.width(8.dp))
                            AppNavigationLabel(item = item, selected = selected)
                        }
                    }
                }
            }
        }

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
internal fun AppNavigationRail(
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
