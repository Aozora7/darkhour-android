package one.aozora.darkhour.ui.settings

import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.HealthImportPhase

internal enum class HealthDataRangeOption(
    val label: String,
    val testTag: String,
) {
    DEFAULT("Last 30 days", "health_range_default"),
    CUSTOM("Custom", "health_range_custom"),
    HISTORY("All history", "health_range_history");

    fun isSelected(range: HealthDataRange): Boolean = when (this) {
        DEFAULT -> range == HealthDataRange.DEFAULT_PERIOD
        CUSTOM -> range is HealthDataRange.Custom
        HISTORY -> range == HealthDataRange.ENTIRE_HISTORY
    }

    fun toRange(customDays: Int): HealthDataRange = when (this) {
        DEFAULT -> HealthDataRange.DEFAULT_PERIOD
        CUSTOM -> HealthDataRange.custom(customDays)
        HISTORY -> HealthDataRange.ENTIRE_HISTORY
    }
}

internal val HealthDataRangeOptions = HealthDataRangeOption.entries

internal fun importStatusText(
    importPhase: HealthImportPhase,
    importedRecordCount: Int,
    expectedRecordCount: Int?,
    isImportPartial: Boolean,
    visibleRecordCount: Int,
): String {
    val count = maxOf(importedRecordCount, visibleRecordCount)
    return when (importPhase) {
        HealthImportPhase.RECENT -> "Loading recent sleep data..."
        HealthImportPhase.HISTORY -> if (isImportPartial) {
            val expected = expectedRecordCount?.let { " of $it" } ?: ""
            "Loading older history... $count$expected records found"
        } else {
            "Loading older history..."
        }
        HealthImportPhase.IDLE -> if (isImportPartial) {
            "$count sleep records imported; full history still loading"
        } else {
            "Refreshing sleep data..."
        }
    }
}
