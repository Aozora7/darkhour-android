package one.aozora.darkhour.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.core.circadian.csf.CsfAnalysis
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.periodogram.PeriodogramResult
import java.time.Duration
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    records: List<SleepRecord>,
    analysis: CsfAnalysis,
    periodogram: PeriodogramResult,
    modifier: Modifier = Modifier,
) {
    val mainSleeps = records.filter { it.isMainSleep }
    val metrics = calculateStatsMetrics(records, analysis.globalDailyDrift)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                ) {
                    HeaderText(mainSleeps.size)
                    Spacer(Modifier.height(16.dp))
                    PeriodogramChart(
                        result = periodogram,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        Metric("Tau", "%.2f h".format(analysis.globalTau), signedMinutes(analysis.globalDailyDrift * 60.0)),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric("Peak period", "%.2f h".format(periodogram.peakPeriod), "Power %.2f".format(periodogram.peakPower)),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric(
                            "Sleep per day",
                            metrics.sleepHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                            metrics.daySpan.takeIf { it > 0 }?.let { "Across $it days" } ?: "No data",
                        ),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        Metric(
                            "Time in bed per day",
                            metrics.timeInBedHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                            metrics.efficiencyPercent?.let { "$it% efficiency" } ?: "No efficiency",
                        ),
                        Modifier.fillMaxWidth()
                    )
                    MetricCard(
                        metric = Metric(
                            "Cumulative shift",
                            metrics.cumulativeShiftDays?.let(::signedDays) ?: "—",
                            metrics.daySpan.takeIf { it > 0 }?.let { "Over $it days" } ?: "No data",
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("stats_screen")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeaderText(mainSleeps.size)

                PeriodogramChart(
                    result = periodogram,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 260.dp)
                        .aspectRatio(1.75f),
                )

                MetricRow(
                    left = Metric("Tau", "%.2f h".format(analysis.globalTau), signedMinutes(analysis.globalDailyDrift * 60.0)),
                    right = Metric("Peak period", "%.2f h".format(periodogram.peakPeriod), "Power %.2f".format(periodogram.peakPower)),
                )
                MetricRow(
                    left = Metric(
                        "Sleep per day",
                        metrics.sleepHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                        metrics.daySpan.takeIf { it > 0 }?.let { "Across $it days" } ?: "No data",
                    ),
                    right = Metric(
                        "Time in bed per day",
                        metrics.timeInBedHoursPerDay?.let { "%.1f h".format(it) } ?: "—",
                        metrics.efficiencyPercent?.let { "$it% efficiency" } ?: "No efficiency",
                    ),
                )
                MetricCard(
                    metric = Metric(
                        "Cumulative shift",
                        metrics.cumulativeShiftDays?.let(::signedDays) ?: "—",
                        metrics.daySpan.takeIf { it > 0 }?.let { "Over $it days" } ?: "No data",
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HeaderText(mainSleepsCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Circadian stats", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Health Connect · $mainSleepsCount main sleeps",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private data class Metric(val label: String, val value: String, val detail: String)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricCard(left, Modifier.weight(1f))
        MetricCard(right, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
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

private fun signedMinutes(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f min/day".format(value)

private fun signedDays(value: Double): String =
    "${if (value >= 0) "+" else ""}%.1f days".format(value)

internal data class StatsMetrics(
    val daySpan: Int,
    val sleepHoursPerDay: Double?,
    val timeInBedHoursPerDay: Double?,
    val efficiencyPercent: Int?,
    val cumulativeShiftDays: Double?,
)

internal fun calculateStatsMetrics(
    records: List<SleepRecord>,
    dailyDriftHours: Double,
): StatsMetrics {
    if (records.isEmpty()) {
        return StatsMetrics(
            daySpan = 0,
            sleepHoursPerDay = null,
            timeInBedHoursPerDay = null,
            efficiencyPercent = null,
            cumulativeShiftDays = null,
        )
    }

    val firstStart = records.minOf { it.startTime }
    val lastEnd = records.maxOf { it.endTime }
    val daySpan = (Duration.between(firstStart, lastEnd).toMillis() / MILLIS_PER_DAY)
        .roundToInt() + 1
    val totalTimeInBedHours = records.sumOf { it.durationHours }
    val totalMinutesAsleep = records.sumOf { it.minutesAsleep }
    val totalTimeInBedMinutes = totalTimeInBedHours * 60.0

    return StatsMetrics(
        daySpan = daySpan,
        sleepHoursPerDay = totalMinutesAsleep / 60.0 / daySpan,
        timeInBedHoursPerDay = totalTimeInBedHours / daySpan,
        efficiencyPercent = totalTimeInBedMinutes
            .takeIf { it > 0.0 }
            ?.let { (totalMinutesAsleep / it * 100.0).roundToInt() },
        cumulativeShiftDays = dailyDriftHours * daySpan / 24.0,
    )
}

private const val MILLIS_PER_DAY = 86_400_000.0
