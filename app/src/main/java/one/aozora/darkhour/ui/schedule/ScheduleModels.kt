package one.aozora.darkhour.ui.schedule

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Immutable
data class ScheduleEntry(
    val id: Long,
    val label: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val date: LocalDate? = null,
    val color: Long = DEFAULT_SCHEDULE_COLOR,
    val enabled: Boolean = true,
) {
    val isWeekly: Boolean get() = daysOfWeek.isNotEmpty()
}

const val DEFAULT_SCHEDULE_COLOR: Long = 0xFF34C759
