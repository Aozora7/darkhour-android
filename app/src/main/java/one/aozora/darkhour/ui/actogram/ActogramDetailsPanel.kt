package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import one.aozora.darkhour.ui.theme.CircadianForecast
import one.aozora.darkhour.ui.theme.CircadianObserved
import one.aozora.darkhour.ui.theme.SleepDeep
import one.aozora.darkhour.ui.theme.SleepLight
import one.aozora.darkhour.ui.theme.SleepRem
import one.aozora.darkhour.ui.theme.SleepWake
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ActogramDetailsPanel(
    selection: ActogramSelection,
    useIsoDateTime: Boolean,
    onEditScheduleEntry: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val use24HourTime =
        useIsoDateTime || android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val accent = when (selection) {
        is ActogramSelection.Sleep -> ActogramLightColor
        is ActogramSelection.Circadian ->
            if (selection.isForecast) ActogramForecastColor else ActogramNightColor
        is ActogramSelection.Schedule -> Color(selection.entry.color)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("actogram_details"),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        ),
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
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (selection) {
                            is ActogramSelection.Sleep -> Icons.Outlined.Hotel
                            is ActogramSelection.Circadian -> Icons.Outlined.Nightlight
                            is ActogramSelection.Schedule -> Icons.AutoMirrored.Outlined.EventNote
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = when (selection) {
                            is ActogramSelection.Sleep -> "Sleep Record"
                            is ActogramSelection.Circadian -> "Circadian Window"
                            is ActogramSelection.Schedule -> selection.entry.label.ifBlank { "Schedule Entry" }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (selection) {
                            is ActogramSelection.Sleep -> "Health Connect"
                            is ActogramSelection.Circadian -> if (selection.isForecast) "Forecast" else "Observed"
                            is ActogramSelection.Schedule -> if (selection.entry.isWeekly) "Weekly" else "Dated"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .testTag("dismiss_actogram_details")
                        .size(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            when (selection) {
                is ActogramSelection.Sleep ->
                    SleepDetails(selection, use24HourTime, useIsoDateTime)
                is ActogramSelection.Circadian ->
                    CircadianDetails(selection, use24HourTime, useIsoDateTime)
                is ActogramSelection.Schedule ->
                    ScheduleDetails(selection, use24HourTime, useIsoDateTime, onEditScheduleEntry)
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
            val startDate = selection.startTime.atOffset(selection.startZoneOffset).toLocalDate()
            DetailLabel("Date")
            Text(
                formatActogramDate(startDate, useIsoDateTime),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                formatActogramDayOfWeek(startDate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            DetailLabel("Time")
            Text(
                "${formatActogramClock(selection.startTime, selection.startZoneOffset, use24HourTime)} - " +
                    formatActogramClock(selection.endTime, selection.endZoneOffset, use24HourTime),
                style = MaterialTheme.typography.bodyLarge,
                color = ActogramLightColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                formatDuration(selection.startTime, selection.endTime),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Text(
                selection.sleepScore?.let { "Score: ${(it * 100).toInt()}/100" } ?: "Score unavailable",
                style = MaterialTheme.typography.labelMedium,
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
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.ExtraBold,
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
                formatActogramDate(selection.date, useIsoDateTime),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                formatActogramDayOfWeek(selection.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ScheduleDetails(
    selection: ActogramSelection.Schedule,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean,
    onEditScheduleEntry: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f)) {
                DetailLabel("Time")
                Text(
                    "${formatActogramClock(selection.occurrenceStart, selection.zoneOffset, use24HourTime)} - " +
                        formatActogramClock(selection.occurrenceEnd, selection.zoneOffset, use24HourTime),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(selection.entry.color),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.weight(1f)) {
                DetailLabel("Schedule")
                Text(
                    selection.entry.recurrenceSummary(useIsoDateTime),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (selection.entry.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = { onEditScheduleEntry(selection.entry.id) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Edit")
        }
    }
}

private fun formatDuration(start: Instant, end: Instant): String {
    val hours = Duration.between(start, end).toMinutes() / 60.0
    return "${"%.1f".format(Locale.getDefault(), hours)} hrs"
}

private fun ScheduleEntry.recurrenceSummary(useIsoDateTime: Boolean): String {
    date?.let { return formatActogramDate(it, useIsoDateTime) }
    val ordered = DayOfWeek.entries.filter { it in daysOfWeek }
    if (ordered.size == 7) return "Every day"
    return ordered.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

private val ActogramDeepColor = SleepDeep
private val ActogramLightColor = SleepLight
private val ActogramRemColor = SleepRem
private val ActogramWakeColor = SleepWake
private val ActogramNightColor = CircadianObserved
private val ActogramForecastColor = CircadianForecast
