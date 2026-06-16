package one.aozora.darkhour.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class SleepStages(
    val deep: Int,
    val light: Int,
    val rem: Int,
    val wake: Int,
)

enum class SleepStageLevel {
    WAKE,
    LIGHT,
    DEEP,
    REM,
}

data class SleepStageInterval(
    val startTime: Instant,
    val level: SleepStageLevel,
    val seconds: Int,
)

data class SleepRecord(
    val logId: Long,
    val dateOfSleep: LocalDate,
    val startTime: Instant,
    val endTime: Instant,
    val durationMs: Long,
    val durationHours: Double,
    val efficiency: Int,
    val minutesAsleep: Int,
    val minutesAwake: Int,
    val isMainSleep: Boolean,
    val sleepScore: Double? = null,
    val stages: SleepStages? = null,
    val stageData: List<SleepStageInterval> = emptyList(),
    val startZoneOffset: ZoneOffset? = null,
    val endZoneOffset: ZoneOffset? = null,
)
