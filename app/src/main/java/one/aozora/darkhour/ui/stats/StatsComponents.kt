package one.aozora.darkhour.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun HeaderText(
    scopeSummary: String,
    dataScope: StatsDataScope,
    showDataScopeToggle: Boolean,
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
    onSelected: (StatsDataScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsDataScope.entries.forEach { scope ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onSelected(scope) },
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
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TauYearChart(
            series = series,
            modifier = modifier
                .height(260.dp)
                .testTag("tau_year_chart"),
        )
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
