package one.aozora.darkhour.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun HeaderText(
    scopeSummary: String,
    dataScope: StatsDataScope,
    showDataScopeToggle: Boolean,
    allAvailableEnabled: Boolean,
    statusMessage: String?,
    onDataScopeChange: (StatsDataScope) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Circadian stats", style = MaterialTheme.typography.headlineSmall)
        Text(
            scopeSummary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (showDataScopeToggle) {
            StatsScopeToggle(
                selectedScope = dataScope,
                allAvailableEnabled = allAvailableEnabled,
                onSelected = onDataScopeChange,
            )
        }
        if (statusMessage != null) {
            Text(
                statusMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatsScopeToggle(
    selectedScope: StatsDataScope,
    allAvailableEnabled: Boolean,
    onSelected: (StatsDataScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsDataScope.entries.forEach { scope ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onSelected(scope) },
                enabled = scope != StatsDataScope.AllAvailable || allAvailableEnabled,
                label = { Text(scope.label) },
                modifier = Modifier.testTag(scope.testTag),
            )
        }
    }
}

internal data class Metric(val label: String, val value: String, val detail: String)

@Composable
internal fun YearlyTauSection(
    series: List<YearlyTauSeries>,
    selectedYears: Set<Int>,
    onSelectedYearsChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val yearListState = rememberLazyListState()
    val sortedSeries = series.sortedByDescending { it.year }
    val minYear = series.minOfOrNull { it.year } ?: 0
    val maxYear = series.maxOfOrNull { it.year } ?: 0
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tau_year_selector")
                .pointerInput(yearListState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var previousPosition = down.position
                        var horizontalDrag = false
                        var verticalGesture = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val displacement = change.position - down.position
                            if (!horizontalDrag && !verticalGesture) {
                                when {
                                    kotlin.math.abs(displacement.y) > viewConfiguration.touchSlop &&
                                        kotlin.math.abs(displacement.y) > kotlin.math.abs(displacement.x) -> {
                                        verticalGesture = true
                                    }
                                    kotlin.math.abs(displacement.x) > viewConfiguration.touchSlop &&
                                        kotlin.math.abs(displacement.x) > kotlin.math.abs(displacement.y) -> {
                                        horizontalDrag = true
                                    }
                                }
                            }

                            if (horizontalDrag) {
                                yearListState.dispatchRawDelta(previousPosition.x - change.position.x)
                                change.consume()
                            }

                            previousPosition = change.position
                            if (!change.pressed) break
                        }
                    }
                },
            state = yearListState,
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = sortedSeries,
                key = YearlyTauSeries::year,
            ) { yearly ->
                val selected = yearly.year in selectedYears
                FilterChip(
                    selected = selected,
                    onClick = {
                        onSelectedYearsChange(
                            if (selected) selectedYears - yearly.year else selectedYears + yearly.year,
                        )
                    },
                    label = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            YearColorMarker(yearGradientColor(yearly.year, minYear, maxYear))
                            Text(yearly.year.toString())
                        }
                    },
                    modifier = Modifier.testTag("tau_year_${yearly.year}"),
                )
            }
        }
        TauYearChart(
            series = sortedSeries.filter { it.year in selectedYears },
            colorYearRange = minYear..maxYear,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag("tau_year_chart"),
        )
    }
}

@Composable
internal fun StatsLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier
                .size(36.dp)
                .testTag("stats_all_data_loading"),
        )
    }
}

@Composable
private fun YearColorMarker(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        drawCircle(color)
    }
}

@Composable
internal fun MetricRow(left: Metric, right: Metric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricCard(left, Modifier.weight(1f))
        MetricCard(right, Modifier.weight(1f))
    }
}

@Composable
internal fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, style = MaterialTheme.typography.headlineSmall)
            Text(metric.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun signedMinutes(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f min/day".format(value)

internal fun signedDays(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f days".format(value)
