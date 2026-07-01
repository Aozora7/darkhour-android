package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActogramOptionsSheet(
    options: ActogramDisplayOptions,
    onOptionsChange: (ActogramDisplayOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxContentHeight = LocalWindowInfo.current.containerDpSize.height * 0.82f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("actogram_options_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
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
            OptionSwitch("Schedule", options.showSchedule) {
                onOptionsChange(options.copy(showSchedule = it))
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
            TextButton(
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
