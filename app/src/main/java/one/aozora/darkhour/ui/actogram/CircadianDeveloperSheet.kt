package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianNumericParameter
import one.aozora.darkhour.ui.DeveloperCircadianState
import one.aozora.darkhour.ui.DeveloperSleepInjectionState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CircadianDeveloperSheet(
    state: DeveloperCircadianState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val maxContentHeight = LocalWindowInfo.current.containerDpSize.height * 0.65f
    val algorithm = state.activeAlgorithm
    val values = CircadianAlgorithmRegistry.resolvedValues(algorithm.id, state.activeOverrides)
    var selectedTab by remember { mutableStateOf(DeveloperTab.PARAMETERS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("circadian_developer_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            DeveloperTabPicker(selectedTab = selectedTab, onSelect = { selectedTab = it })
            when (selectedTab) {
                DeveloperTab.PARAMETERS -> {
                    AlgorithmPicker(
                        selectedId = algorithm.id,
                        onSelect = state.onAlgorithmChange,
                    )
                    algorithm.parameters.forEach { parameter ->
                        CircadianParameterRow(
                            parameter = parameter,
                            value = values.getValue(parameter.key),
                            isOverridden = parameter.key in state.activeOverrides,
                            onValueChange = { state.onParameterChange(parameter.key, it) },
                            onReset = { state.onParameterReset(parameter.key) },
                        )
                    }
                }
                DeveloperTab.SLEEP_INJECTION -> SleepInjectionControls(state.sleepInjection)
            }
        }
    }
}

@Composable
private fun DeveloperTabPicker(
    selectedTab: DeveloperTab,
    onSelect: (DeveloperTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DeveloperTab.entries.forEach { tab ->
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                label = { Text(tab.label) },
                modifier = Modifier.testTag("circadian_developer_tab_${tab.tag}"),
            )
        }
    }
}

@Composable
private fun SleepInjectionControls(state: DeveloperSleepInjectionState) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.date,
            onValueChange = { state.onFormChange(form.copy(date = it)) },
            label = { Text("Date") },
            supportingText = { Text("YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sleep_injection_date"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.timeFrom,
                onValueChange = { state.onFormChange(form.copy(timeFrom = it)) },
                label = { Text("Time from") },
                supportingText = { Text("HH:mm") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("sleep_injection_time_from"),
            )
            OutlinedTextField(
                value = form.timeTo,
                onValueChange = { state.onFormChange(form.copy(timeTo = it)) },
                label = { Text("Time to") },
                supportingText = { Text("HH:mm") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("sleep_injection_time_to"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.driftMinutesPerDay,
                onValueChange = { state.onFormChange(form.copy(driftMinutesPerDay = it)) },
                label = { Text("Drift per day (min)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("sleep_injection_drift"),
            )
            OutlinedTextField(
                value = form.numberOfDays,
                onValueChange = { state.onFormChange(form.copy(numberOfDays = it)) },
                label = { Text("Number of days") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("sleep_injection_days"),
            )
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "Injected records: ${state.injectedRecordCount}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("sleep_injection_count"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = state.onAdd,
                modifier = Modifier.weight(1f).testTag("sleep_injection_add"),
            ) {
                Text("Add")
            }
            OutlinedButton(
                onClick = state.onClear,
                modifier = Modifier.weight(1f).testTag("sleep_injection_clear"),
            ) {
                Text("Clear")
            }
        }
    }
}

private enum class DeveloperTab(val label: String, val tag: String) {
    PARAMETERS("Parameters", "parameters"),
    SLEEP_INJECTION("Sleep injection", "sleep_injection"),
}

@Composable
private fun AlgorithmPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircadianAlgorithmRegistry.algorithms.forEach { algorithm ->
            FilterChip(
                selected = algorithm.id == selectedId,
                onClick = { onSelect(algorithm.id) },
                label = { Text(algorithm.displayName, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.testTag("circadian_algorithm_${algorithm.id}"),
            )
        }
    }
}

@Composable
private fun CircadianParameterRow(
    parameter: CircadianNumericParameter,
    value: Double,
    isOverridden: Boolean,
    onValueChange: (Double) -> Unit,
    onReset: () -> Unit,
) {
    var sliderValue by remember(parameter.key, value) { mutableFloatStateOf(value.toFloat()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = parameter.label,
            modifier = Modifier.width(104.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue.toDouble()) },
            valueRange = parameter.minValue.toFloat()..parameter.maxValue.toFloat(),
            steps = parameter.steps,
            modifier = Modifier
                .weight(1f)
                .testTag("circadian_parameter_${parameter.key}"),
        )
        Text(
            text = formatParameter(sliderValue.toDouble(), parameter),
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
        IconButton(
            onClick = onReset,
            enabled = isOverridden,
            modifier = Modifier.testTag("circadian_reset_${parameter.key}"),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Reset ${parameter.label}")
        }
    }
}

private fun formatParameter(value: Double, parameter: CircadianNumericParameter): String {
    val formatted = String.format(Locale.ROOT, "%.${parameter.decimalPlaces}f", value)
    return if (parameter.unit.isBlank()) formatted else "$formatted ${parameter.unit}"
}
