package one.aozora.darkhour.ui

import androidx.compose.runtime.Immutable

enum class DarkHourDestination(val label: String) {
    ACTOGRAM("Actogram"),
    STATS("Stats"),
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
    val colorMode: ActogramColorMode = ActogramColorMode.STAGES,
    val timeScale: ActogramTimeScale = ActogramTimeScale.HOURS_24,
    val customHours: Float = 24f,
    val order: ActogramOrder = ActogramOrder.NEWEST_FIRST,
)

@Immutable
data class AppSettings(
    val includeNaps: Boolean = true,
    val forecastDays: Int = 2,
    val hapticFeedback: Boolean = true,
    val useIsoDateTime: Boolean = false,
)
