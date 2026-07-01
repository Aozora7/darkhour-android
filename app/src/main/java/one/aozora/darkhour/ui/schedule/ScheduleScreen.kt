package one.aozora.darkhour.ui.schedule

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.LocalScheduleState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
) {
    val (
        entries,
        pendingEditId,
        onEntriesChange,
        onEditConsumed,
    ) = LocalScheduleState.current
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val use24HourTime = android.text.format.DateFormat.is24HourFormat(context)
    var editingEntry by remember { mutableStateOf<ScheduleEntry?>(null) }
    var addingEntry by remember { mutableStateOf(false) }

    LaunchedEffect(pendingEditId, entries) {
        val entryId = pendingEditId ?: return@LaunchedEffect
        editingEntry = entries.firstOrNull { it.id == entryId }
        addingEntry = false
        onEditConsumed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("schedule_screen"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Schedule", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Weekly blocks and dated events appear on the actogram.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            if (entries.isEmpty()) {
                EmptySchedule()
            } else {
                entries.sortedWith(compareBy<ScheduleEntry> { it.startTime }.thenBy { it.label })
                    .forEach { entry ->
                        ScheduleRow(
                            entry = entry,
                            locale = locale,
                            use24HourTime = use24HourTime,
                            onToggle = { enabled ->
                                onEntriesChange(entries.map { if (it.id == entry.id) it.copy(enabled = enabled) else it })
                            },
                            onEdit = { editingEntry = entry },
                        )
                    }
            }
            Spacer(Modifier.height(72.dp))
        }

        FloatingActionButton(
            onClick = { addingEntry = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_schedule_entry"),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add schedule entry")
        }
    }

    val entryForSheet = when {
        addingEntry -> null
        editingEntry != null -> editingEntry
        else -> return
    }
    ScheduleEntrySheet(
        entry = entryForSheet,
        onDismiss = {
            addingEntry = false
            editingEntry = null
        },
        onDelete = entryForSheet?.let { entry ->
            {
                onEntriesChange(entries.filterNot { it.id == entry.id })
                editingEntry = null
            }
        },
        onSave = { saved ->
            val updated = if (entries.any { it.id == saved.id }) {
                entries.map { if (it.id == saved.id) saved else it }
            } else {
                entries + saved
            }
            onEntriesChange(updated)
            addingEntry = false
            editingEntry = null
        },
    )
}

@Composable
private fun EmptySchedule() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            "No schedule entries",
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleRow(
    entry: ScheduleEntry,
    locale: java.util.Locale,
    use24HourTime: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Color(entry.color), CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${formatTime(entry.startTime, use24HourTime)} - ${formatTime(entry.endTime, use24HourTime)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.label.ifBlank { "Schedule entry" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.recurrenceLabel(locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit schedule entry")
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEntrySheet(
    entry: ScheduleEntry?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (ScheduleEntry) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val dateFormatter = remember(locale) { dateFormatter(locale) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by remember(entry) { mutableStateOf(entry?.label ?: "") }
    var startTime by remember(entry) { mutableStateOf(entry?.startTime ?: LocalTime.of(9, 0)) }
    var endTime by remember(entry) { mutableStateOf(entry?.endTime ?: LocalTime.of(17, 0)) }
    var days by remember(entry) { mutableStateOf(entry?.daysOfWeek ?: emptySet()) }
    var date by remember(entry) { mutableStateOf(entry?.date) }
    var color by remember(entry) { mutableLongStateOf(entry?.color ?: DEFAULT_SCHEDULE_COLOR) }
    var confirmDelete by remember(entry) { mutableStateOf(false) }
    val canSave = days.isNotEmpty() || date != null
    val use24HourTime = android.text.format.DateFormat.is24HourFormat(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("schedule_entry_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (entry == null) "Add schedule" else "Edit schedule",
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeButton("Start", startTime, use24HourTime, Modifier.weight(1f)) {
                    showTimePicker(context, startTime) { startTime = it }
                }
                TimeButton("End", endTime, use24HourTime, Modifier.weight(1f)) {
                    showTimePicker(context, endTime) { endTime = it }
                }
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                RepeatPresetChip("Weekdays") {
                    date = null
                    days = Weekdays
                }
                RepeatPresetChip("Weekend") {
                    date = null
                    days = Weekend
                }
                RepeatPresetChip("Every day") {
                    date = null
                    days = EveryDay
                }
                RepeatPresetChip("Clear") {
                    date = null
                    days = emptySet()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { day ->
                    val selected = day in days
                    FilterChip(
                        selected = selected,
                        onClick = {
                            date = null
                            days = if (selected) days - day else days + day
                        },
                        label = { Text(day.getDisplayName(TextStyle.NARROW, locale)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val initial = date ?: LocalDate.now()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                date = LocalDate.of(year, month + 1, dayOfMonth)
                                days = emptySet()
                            },
                            initial.year,
                            initial.monthValue - 1,
                            initial.dayOfMonth,
                        ).show()
                    },
                    label = { Text(date?.format(dateFormatter) ?: "Specific date") },
                )
                if (date != null) {
                    TextButton(onClick = { date = null }) {
                        Text("Clear")
                    }
                }
            }
            if (!canSave) {
                Text(
                    "Choose at least one day or one specific date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text("Color", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScheduleColors.forEach { swatch ->
                    ColorSwatch(
                        color = swatch,
                        selected = color == swatch,
                        onClick = { color = swatch },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                ScheduleEntry(
                                    id = entry?.id ?: System.currentTimeMillis(),
                                    label = label.trim(),
                                    startTime = startTime,
                                    endTime = endTime,
                                    daysOfWeek = days,
                                    date = date,
                                    color = color,
                                    enabled = entry?.enabled ?: true,
                                ),
                            )
                        },
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete schedule?") },
            text = { Text("This schedule entry will be removed from the actogram.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RepeatPresetChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun TimeButton(
    label: String,
    time: LocalTime,
    use24HourTime: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(time, use24HourTime), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color(color), CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

private fun showTimePicker(
    context: android.content.Context,
    initial: LocalTime,
    onPicked: (LocalTime) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
        initial.hour,
        initial.minute,
        android.text.format.DateFormat.is24HourFormat(context),
    ).show()
}

private fun ScheduleEntry.recurrenceLabel(locale: java.util.Locale): String {
    date?.let { return it.format(dateFormatter(locale)) }
    val ordered = DayOfWeek.entries.filter { it in daysOfWeek }
    if (ordered.size == 7) return "Every day"
    return ordered.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

private fun formatTime(time: LocalTime, use24HourTime: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (use24HourTime) "HH:mm" else "h:mm a"))

private fun dateFormatter(locale: java.util.Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", locale)

private val Weekdays = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

private val Weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

private val EveryDay = DayOfWeek.entries.toSet()

private val ScheduleColors = listOf(
    DEFAULT_SCHEDULE_COLOR,
    0xFF20E0B8,
    0xFF3FA9F5,
    0xFFFFC533,
    0xFFFF6FAE,
    0xFFB794FF,
)
