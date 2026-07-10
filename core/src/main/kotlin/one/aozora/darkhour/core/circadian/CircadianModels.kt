package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate

const val GAP_THRESHOLD_DAYS = 14

enum class CircadianConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class CircadianDay(
    val date: LocalDate,
    val nightStartHour: Double,
    val nightEndHour: Double,
    /** Algorithm-native confidence retained for diagnostics and details. */
    val confidenceScore: Double,
    /** Cross-algorithm normalized confidence used only for overlay opacity. */
    val overlayConfidence: Double = confidenceScore,
    val confidence: CircadianConfidence,
    val localTau: Double,
    val localDrift: Double,
    val anchorSleep: SleepRecord? = null,
    val isForecast: Boolean,
    val isGap: Boolean,
)

interface CircadianAnalysis {
    val globalTau: Double
    val globalDailyDrift: Double
    val days: List<CircadianDay>
    val algorithmId: String
    val tau: Double
    val dailyDrift: Double
    val rSquared: Double
}
