package one.aozora.darkhour.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.ui.LocalScheduleState

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
