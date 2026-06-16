package one.aozora.darkhour.ui.actogram

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.ActogramColorMode
import one.aozora.darkhour.ui.ActogramDisplayOptions
import one.aozora.darkhour.ui.ActogramOrder
import one.aozora.darkhour.ui.ActogramTimeScale
import one.aozora.darkhour.ui.theme.ChartSelection
import one.aozora.darkhour.ui.theme.CircadianForecast
import one.aozora.darkhour.ui.theme.CircadianObserved
import one.aozora.darkhour.ui.theme.SleepDeep
import one.aozora.darkhour.ui.theme.SleepLight
import one.aozora.darkhour.ui.theme.SleepRem
import one.aozora.darkhour.ui.theme.SleepWake
import java.time.Instant
import java.time.Duration
import java.util.Locale

@Composable
fun ActogramScreen(
    layout: ActogramLayout,
    options: ActogramDisplayOptions,
    useIsoDateTime: Boolean,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
    onTransformingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOptions by remember { mutableStateOf(false) }
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
            FloatingActionButton(
                onClick = { showOptions = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("actogram_options"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "Visualization options")
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
                    useIsoDateTime = useIsoDateTime,
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
}

@Composable
private fun ActogramDetailsPanel(
    selection: ActogramSelection,
    useIsoDateTime: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val use24HourTime =
        useIsoDateTime || android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val accent = when (selection) {
        is ActogramSelection.Sleep -> ActogramLightColor
        is ActogramSelection.Circadian ->
            if (selection.isForecast) ActogramForecastColor else ActogramNightColor
    }
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("actogram_details"),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (selection) {
                            is ActogramSelection.Sleep -> Icons.Outlined.Hotel
                            is ActogramSelection.Circadian -> Icons.Outlined.Nightlight
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                ) {
                    Text(
                        text = when (selection) {
                            is ActogramSelection.Sleep -> "Sleep Record"
                            is ActogramSelection.Circadian -> "Circadian Window"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (selection) {
                            is ActogramSelection.Sleep -> "Health Connect"
                            is ActogramSelection.Circadian -> if (selection.isForecast) "Forecast" else "Observed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .testTag("dismiss_actogram_details")
                        .size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            when (selection) {
                is ActogramSelection.Sleep ->
                    SleepDetails(selection, use24HourTime, useIsoDateTime)
                is ActogramSelection.Circadian ->
                    CircadianDetails(selection, use24HourTime, useIsoDateTime)
            }
        }
    }
}

@Composable
private fun SleepDetails(
    selection: ActogramSelection.Sleep,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            DetailLabel("Time")
            Text(
                formatActogramDateTime(
                    selection.startTime,
                    selection.startZoneOffset,
                    use24HourTime,
                    useIsoDateTime,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatActogramDateTime(
                    selection.endTime,
                    selection.endZoneOffset,
                    use24HourTime,
                    useIsoDateTime,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            DetailLabel("Duration / Score")
            Text(
                formatDuration(selection.startTime, selection.endTime),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                selection.sleepScore?.let { "Score: ${(it * 100).toInt()}/100" } ?: "Score unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailLabel("Sleep Stages")
        val stages = selection.stages
        if (stages == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    "No stage data available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageTile("Deep", stages.deep, ActogramDeepColor, Modifier.weight(1f))
                StageTile("Light", stages.light, ActogramLightColor, Modifier.weight(1f))
                StageTile("REM", stages.rem, ActogramRemColor, Modifier.weight(1f))
                StageTile("Wake", stages.wake, ActogramWakeColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StageTile(
    label: String,
    minutes: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun DetailLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CircadianDetails(
    selection: ActogramSelection.Circadian,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            DetailLabel("Date")
            Text(
                "${formatActogramDate(selection.date, useIsoDateTime)} ",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.weight(1f)) {
            DetailLabel("Window")
            Text(
                "${formatActogramClock(selection.startTime, selection.zoneOffset, use24HourTime)} - " +
                    formatActogramClock(selection.endTime, selection.zoneOffset, use24HourTime),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selection.isForecast) ActogramForecastColor else ActogramNightColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Confidence ${(selection.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(start: Instant, end: Instant): String {
    val hours = Duration.between(start, end).toMinutes() / 60.0
    return "${"%.1f".format(Locale.getDefault(), hours)} hrs"
}

private val ActogramDeepColor = SleepDeep
private val ActogramLightColor = SleepLight
private val ActogramRemColor = SleepRem
private val ActogramWakeColor = SleepWake
private val ActogramNightColor = CircadianObserved
private val ActogramForecastColor = CircadianForecast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActogramOptionsSheet(
    options: ActogramDisplayOptions,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("actogram_options_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Actogram", style = MaterialTheme.typography.titleLarge)

            OptionSwitch("Double plot", options.doublePlot) {
                onOptionsChange(options.copy(doublePlot = it))
            }
            OptionSwitch("Date labels", options.showDateLabels) {
                onOptionsChange(options.copy(showDateLabels = it))
            }
            OptionSwitch("Circadian overlay", options.showCircadianOverlay) {
                onOptionsChange(options.copy(showCircadianOverlay = it))
            }

            HorizontalDivider()
            Text("Order", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ActogramOrder.entries.forEachIndexed { index, order ->
                    SegmentedButton(
                        selected = options.order == order,
                        onClick = { onOptionsChange(options.copy(order = order)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ActogramOrder.entries.size),
                        modifier = Modifier.testTag(
                            when (order) {
                                ActogramOrder.NEWEST_FIRST -> "order_newest"
                                ActogramOrder.OLDEST_FIRST -> "order_oldest"
                            },
                        ),
                    ) {
                        Text(
                            when (order) {
                                ActogramOrder.NEWEST_FIRST -> "Newest"
                                ActogramOrder.OLDEST_FIRST -> "Oldest"
                            },
                        )
                    }
                }
            }

            Text("Color", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ActogramColorMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = options.colorMode == mode,
                        onClick = { onOptionsChange(options.copy(colorMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ActogramColorMode.entries.size),
                    ) {
                        Text(
                            when (mode) {
                                ActogramColorMode.STAGES -> "Stages"
                                ActogramColorMode.SLEEP_SCORE -> "Score"
                                ActogramColorMode.SOLID -> "Solid"
                            },
                        )
                    }
                }
            }

            Text("Row width", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ActogramTimeScale.entries.forEachIndexed { index, scale ->
                    SegmentedButton(
                        selected = options.timeScale == scale,
                        onClick = { onOptionsChange(options.copy(timeScale = scale)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ActogramTimeScale.entries.size),
                    ) {
                        Text(
                            when (scale) {
                                ActogramTimeScale.HOURS_24 -> "24 h"
                                ActogramTimeScale.CIRCADIAN_TAU -> "Tau"
                                ActogramTimeScale.CUSTOM -> "Custom"
                            },
                        )
                    }
                }
            }

            if (options.timeScale == ActogramTimeScale.CUSTOM) {
                Text("${"%.1f".format(options.customHours)} hours")
                Slider(
                    value = options.customHours,
                    onValueChange = { onOptionsChange(options.copy(customHours = it)) },
                    valueRange = 22f..28f,
                    steps = 23,
                )
            }

            Text("Vertical scale")
            Slider(
                value = options.rowHeightDp,
                onValueChange = { onOptionsChange(options.copy(rowHeightDp = it)) },
                valueRange = 12f..60f,
            )
            androidx.compose.material3.TextButton(
                onClick = { onOptionsChange(options.copy(rowHeightDp = 22f)) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Reset scale")
            }
        }
    }
}

@Composable
private fun OptionSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
