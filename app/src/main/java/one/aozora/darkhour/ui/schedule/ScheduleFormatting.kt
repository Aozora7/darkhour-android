package one.aozora.darkhour.ui.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal fun ScheduleEntry.recurrenceLabel(locale: Locale): String {
    date?.let { return it.format(scheduleDateFormatter(locale)) }
    val ordered = DayOfWeek.entries.filter { it in daysOfWeek }
    if (ordered.size == 7) return "Every day"
    return ordered.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

internal fun formatScheduleTime(time: LocalTime, use24HourTime: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (use24HourTime) "HH:mm" else "h:mm a"))

internal fun scheduleDateFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", locale)

internal val ScheduleWeekdays = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

internal val ScheduleWeekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

internal val ScheduleEveryDay = DayOfWeek.entries.toSet()

internal val ScheduleColors = listOf(
    DEFAULT_SCHEDULE_COLOR,
    0xFF20E0B8,
    0xFF3FA9F5,
    0xFFFFC533,
    0xFFFF6FAE,
    0xFFB794FF,
)
