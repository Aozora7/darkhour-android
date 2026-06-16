package one.aozora.darkhour.ui

import android.content.Context
import android.content.SharedPreferences

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): AppSettings = preferences.readAppSettings()

    fun readDisplayOptions(): ActogramDisplayOptions = preferences.readActogramDisplayOptions()

    fun write(settings: AppSettings) {
        preferences.edit()
            .putBoolean(INCLUDE_NAPS_KEY, settings.includeNaps)
            .putInt(FORECAST_DAYS_KEY, settings.forecastDays.coerceIn(0, 30))
            .putBoolean(USE_ISO_DATE_TIME_KEY, settings.useIsoDateTime)
            .apply()
    }

    fun writeDisplayOptions(options: ActogramDisplayOptions) {
        preferences.edit()
            .putFloat(ROW_HEIGHT_DP_KEY, options.rowHeightDp.coerceIn(12f, 60f))
            .putBoolean(DOUBLE_PLOT_KEY, options.doublePlot)
            .putBoolean(SHOW_DATE_LABELS_KEY, options.showDateLabels)
            .putBoolean(SHOW_CIRCADIAN_OVERLAY_KEY, options.showCircadianOverlay)
            .putString(COLOR_MODE_KEY, options.colorMode.name)
            .putString(TIME_SCALE_KEY, options.timeScale.name)
            .putFloat(CUSTOM_HOURS_KEY, options.customHours.coerceIn(22f, 28f))
            .putString(ORDER_KEY, options.order.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
    }
}

private const val INCLUDE_NAPS_KEY = "include_naps"
private const val FORECAST_DAYS_KEY = "forecast_days"
private const val USE_ISO_DATE_TIME_KEY = "use_iso_date_time"
private const val ROW_HEIGHT_DP_KEY = "actogram_row_height_dp"
private const val DOUBLE_PLOT_KEY = "actogram_double_plot"
private const val SHOW_DATE_LABELS_KEY = "actogram_show_date_labels"
private const val SHOW_CIRCADIAN_OVERLAY_KEY = "actogram_show_circadian_overlay"
private const val COLOR_MODE_KEY = "actogram_color_mode"
private const val TIME_SCALE_KEY = "actogram_time_scale"
private const val CUSTOM_HOURS_KEY = "actogram_custom_hours"
private const val ORDER_KEY = "actogram_order"

private fun SharedPreferences.readAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        includeNaps = getBoolean(INCLUDE_NAPS_KEY, defaults.includeNaps),
        forecastDays = getInt(FORECAST_DAYS_KEY, defaults.forecastDays).coerceIn(0, 30),
        useIsoDateTime = getBoolean(USE_ISO_DATE_TIME_KEY, defaults.useIsoDateTime),
    )
}

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
        colorMode = getEnum(COLOR_MODE_KEY, defaults.colorMode),
        timeScale = getEnum(TIME_SCALE_KEY, defaults.timeScale),
        customHours = getFloat(CUSTOM_HOURS_KEY, defaults.customHours).coerceIn(22f, 28f),
        order = getEnum(ORDER_KEY, defaults.order),
    )
}

private inline fun <reified T : Enum<T>> SharedPreferences.getEnum(
    key: String,
    defaultValue: T,
): T = getString(key, null)
    ?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } }
    ?: defaultValue
