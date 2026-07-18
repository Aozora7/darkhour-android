package one.aozora.darkhour.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.schedule.ScheduleEntry
import one.aozora.darkhour.ui.settings.AppSettings
import one.aozora.darkhour.ui.settings.PeriodogramRangeSelection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): AppSettings = preferences.readAppSettings()

    fun readDisplayOptions(): ActogramDisplayOptions = preferences.readActogramDisplayOptions()

    fun readScheduleEntries(): List<ScheduleEntry> = preferences.readScheduleEntries()

    fun readHealthDataRange(): HealthDataRange = preferences.readHealthDataRange()

    fun write(settings: AppSettings) {
        preferences.edit {
            putBoolean(INCLUDE_NAPS_KEY, settings.includeNaps)
                .putInt(FORECAST_DAYS_KEY, settings.forecastDays.coerceIn(0, 30))
                .putBoolean(USE_ISO_DATE_TIME_KEY, settings.useIsoDateTime)
                .putBoolean(
                    HISTORY_ACCESS_CALLOUT_DISMISSED_KEY,
                    settings.historyAccessCalloutDismissed,
                )
                .putBoolean(STATS_USE_ALL_DATA_KEY, settings.statsUseAllData)
            if (settings.selectedTauYears == null) {
                remove(SELECTED_TAU_YEARS_KEY)
            } else {
                putStringSet(
                    SELECTED_TAU_YEARS_KEY,
                    settings.selectedTauYears.map(Int::toString).toSet(),
                )
            }
            settings.periodogramRange.newestMonth?.let {
                putString(PERIODOGRAM_NEWEST_MONTH_KEY, it.toString())
            } ?: remove(PERIODOGRAM_NEWEST_MONTH_KEY)
            settings.periodogramRange.oldestMonth?.let {
                putString(PERIODOGRAM_OLDEST_MONTH_KEY, it.toString())
            } ?: remove(PERIODOGRAM_OLDEST_MONTH_KEY)
        }
    }

    fun writeDisplayOptions(options: ActogramDisplayOptions) {
        preferences.edit {
            putFloat(ROW_HEIGHT_DP_KEY, options.rowHeightDp.coerceIn(12f, 60f))
                .putBoolean(DOUBLE_PLOT_KEY, options.doublePlot)
                .putBoolean(SHOW_DATE_LABELS_KEY, options.showDateLabels)
                .putBoolean(SHOW_CIRCADIAN_OVERLAY_KEY, options.showCircadianOverlay)
                .putBoolean(SHOW_SCHEDULE_KEY, options.showSchedule)
                .putString(COLOR_MODE_KEY, options.colorMode.name)
                .putString(TIME_SCALE_KEY, options.timeScale.name)
                .putFloat(CUSTOM_HOURS_KEY, options.customHours.coerceIn(22f, 28f))
                .putString(ORDER_KEY, options.order.name)
        }
    }

    fun writeScheduleEntries(entries: List<ScheduleEntry>) {
        preferences.edit {
            putString(SCHEDULE_ENTRIES_KEY, entries.joinToString("\n") { it.serialize() })
        }
    }

    fun writeHealthDataRange(range: HealthDataRange) {
        preferences.edit {
            when (range) {
                HealthDataRange.DefaultPeriod -> putString(
                    HEALTH_DATA_RANGE_OPTION_KEY,
                    HEALTH_DATA_RANGE_DEFAULT,
                )
                HealthDataRange.EntireHistory -> putString(
                    HEALTH_DATA_RANGE_OPTION_KEY,
                    HEALTH_DATA_RANGE_HISTORY,
                )
                is HealthDataRange.Custom -> {
                    putString(HEALTH_DATA_RANGE_OPTION_KEY, HEALTH_DATA_RANGE_CUSTOM)
                    putInt(
                        HEALTH_DATA_RANGE_DAYS_KEY,
                        range.days.coerceAtLeast(HealthDataRange.MINIMUM_CUSTOM_DAYS),
                    )
                }
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
    }
}

private const val INCLUDE_NAPS_KEY = "include_naps"
private const val FORECAST_DAYS_KEY = "forecast_days"
private const val USE_ISO_DATE_TIME_KEY = "use_iso_date_time"
private const val HISTORY_ACCESS_CALLOUT_DISMISSED_KEY = "history_access_callout_dismissed"
private const val STATS_USE_ALL_DATA_KEY = "stats_use_all_data"
private const val SELECTED_TAU_YEARS_KEY = "stats_selected_tau_years"
private const val PERIODOGRAM_NEWEST_MONTH_KEY = "stats_periodogram_newest_month"
private const val PERIODOGRAM_OLDEST_MONTH_KEY = "stats_periodogram_oldest_month"
private const val ROW_HEIGHT_DP_KEY = "actogram_row_height_dp"
private const val DOUBLE_PLOT_KEY = "actogram_double_plot"
private const val SHOW_DATE_LABELS_KEY = "actogram_show_date_labels"
private const val SHOW_CIRCADIAN_OVERLAY_KEY = "actogram_show_circadian_overlay"
private const val SHOW_SCHEDULE_KEY = "actogram_show_schedule"
private const val COLOR_MODE_KEY = "actogram_color_mode"
private const val TIME_SCALE_KEY = "actogram_time_scale"
private const val CUSTOM_HOURS_KEY = "actogram_custom_hours"
private const val ORDER_KEY = "actogram_order"
private const val SCHEDULE_ENTRIES_KEY = "schedule_entries"
private const val HEALTH_DATA_RANGE_OPTION_KEY = "health_data_range_option"
private const val HEALTH_DATA_RANGE_DAYS_KEY = "health_data_range_days"
private const val HEALTH_DATA_RANGE_DEFAULT = "default"
private const val HEALTH_DATA_RANGE_CUSTOM = "custom"
private const val HEALTH_DATA_RANGE_HISTORY = "history"

private fun SharedPreferences.readAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        includeNaps = getBoolean(INCLUDE_NAPS_KEY, defaults.includeNaps),
        forecastDays = getInt(FORECAST_DAYS_KEY, defaults.forecastDays).coerceIn(0, 30),
        useIsoDateTime = getBoolean(USE_ISO_DATE_TIME_KEY, defaults.useIsoDateTime),
        historyAccessCalloutDismissed = getBoolean(
            HISTORY_ACCESS_CALLOUT_DISMISSED_KEY,
            defaults.historyAccessCalloutDismissed,
        ),
        statsUseAllData = getBoolean(STATS_USE_ALL_DATA_KEY, defaults.statsUseAllData),
        selectedTauYears = if (contains(SELECTED_TAU_YEARS_KEY)) {
            getStringSet(SELECTED_TAU_YEARS_KEY, emptySet())
                .orEmpty()
                .mapNotNull(String::toIntOrNull)
                .toSet()
        } else {
            defaults.selectedTauYears
        },
        periodogramRange = PeriodogramRangeSelection(
            newestMonth = getString(PERIODOGRAM_NEWEST_MONTH_KEY, null)?.toYearMonthOrNull(),
            oldestMonth = getString(PERIODOGRAM_OLDEST_MONTH_KEY, null)?.toYearMonthOrNull(),
        ),
    )
}

private fun String.toYearMonthOrNull(): YearMonth? = runCatching(YearMonth::parse).getOrNull()

private fun SharedPreferences.readActogramDisplayOptions(): ActogramDisplayOptions {
    val defaults = ActogramDisplayOptions()
    return ActogramDisplayOptions(
        rowHeightDp = getFloat(ROW_HEIGHT_DP_KEY, defaults.rowHeightDp).coerceIn(12f, 60f),
        doublePlot = getBoolean(DOUBLE_PLOT_KEY, defaults.doublePlot),
        showDateLabels = getBoolean(SHOW_DATE_LABELS_KEY, defaults.showDateLabels),
        showCircadianOverlay = getBoolean(
            SHOW_CIRCADIAN_OVERLAY_KEY,
            defaults.showCircadianOverlay,
        ),
        showSchedule = getBoolean(SHOW_SCHEDULE_KEY, defaults.showSchedule),
        colorMode = getEnum(COLOR_MODE_KEY, defaults.colorMode),
        timeScale = getEnum(TIME_SCALE_KEY, defaults.timeScale),
        customHours = getFloat(CUSTOM_HOURS_KEY, defaults.customHours).coerceIn(22f, 28f),
        order = getEnum(ORDER_KEY, defaults.order),
    )
}

private fun SharedPreferences.readScheduleEntries(): List<ScheduleEntry> =
    getString(SCHEDULE_ENTRIES_KEY, null)
        ?.lineSequence()
        ?.mapNotNull { it.deserializeScheduleEntry() }
        ?.toList()
        ?: emptyList()

private fun SharedPreferences.readHealthDataRange(): HealthDataRange =
    when (getString(HEALTH_DATA_RANGE_OPTION_KEY, HEALTH_DATA_RANGE_DEFAULT)) {
        HEALTH_DATA_RANGE_CUSTOM -> HealthDataRange.custom(
            getInt(HEALTH_DATA_RANGE_DAYS_KEY, HealthDataRange.DEFAULT_CUSTOM_DAYS),
        )
        HEALTH_DATA_RANGE_HISTORY -> HealthDataRange.ENTIRE_HISTORY
        else -> HealthDataRange.DEFAULT_PERIOD
    }

private fun ScheduleEntry.serialize(): String = listOf(
    id.toString(),
    label.encodeScheduleField(),
    startTime.toString(),
    endTime.toString(),
    daysOfWeek.map { it.value }.sorted().joinToString(","),
    date?.toString().orEmpty(),
    color.toString(16),
    enabled.toString(),
).joinToString("|")

private fun String.deserializeScheduleEntry(): ScheduleEntry? {
    val parts = split("|")
    if (parts.size != 8) return null
    return runCatching {
        ScheduleEntry(
            id = parts[0].toLong(),
            label = parts[1].decodeScheduleField(),
            startTime = LocalTime.parse(parts[2]),
            endTime = LocalTime.parse(parts[3]),
            daysOfWeek = parts[4]
                .split(",")
                .filter { it.isNotBlank() }
                .map { DayOfWeek.of(it.toInt()) }
                .toSet(),
            date = parts[5].takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            color = parts[6].toLong(16),
            enabled = parts[7].toBooleanStrictOrNull() ?: true,
        )
    }.getOrNull()?.takeIf { it.daysOfWeek.isNotEmpty() xor (it.date != null) }
}

private fun String.encodeScheduleField(): String =
    replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

private fun String.decodeScheduleField(): String =
    replace("%0A", "\n").replace("%7C", "|").replace("%25", "%")

private inline fun <reified T : Enum<T>> SharedPreferences.getEnum(
    key: String,
    defaultValue: T,
): T = getString(key, null)
    ?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } }
    ?: defaultValue
