package one.aozora.darkhour.ui

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class DarkHourDestination(val label: String) {
    ACTOGRAM("Actogram"),
    STATS("Stats"),
    SCHEDULE("Schedule"),
    SETTINGS("Settings"),
}

enum class ActogramColorMode {
    STAGES,
    SLEEP_SCORE,
    SOLID,
}

enum class ActogramTimeScale {
    HOURS_24,
    CIRCADIAN_TAU,
    CUSTOM,
}

enum class ActogramOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

@Immutable
data class ActogramDisplayOptions(
    val rowHeightDp: Float = 22f,
    val doublePlot: Boolean = false,
    val showDateLabels: Boolean = true,
    val showCircadianOverlay: Boolean = true,
    val showSchedule: Boolean = true,
    val colorMode: ActogramColorMode = ActogramColorMode.STAGES,
    val timeScale: ActogramTimeScale = ActogramTimeScale.HOURS_24,
    val customHours: Float = 24f,
    val order: ActogramOrder = ActogramOrder.NEWEST_FIRST,
)

@Immutable
data class AppSettings(
    val includeNaps: Boolean = true,
    val forecastDays: Int = 2,
    val useIsoDateTime: Boolean = false,
    val historyAccessCalloutDismissed: Boolean = false,
)

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
