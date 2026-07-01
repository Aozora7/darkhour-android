package one.aozora.darkhour.ui.actogram

import androidx.compose.runtime.Immutable

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
