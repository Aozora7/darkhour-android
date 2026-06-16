package one.aozora.darkhour.data

import androidx.health.connect.client.records.SleepSessionRecord
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.core.model.calculateSleepScore
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneId

/**
 * App-layer import envelope for Health Connect mapping.
 *
 * The core algorithm consumes only [SleepRecord]. Health Connect metadata stays
 * beside the imported record so re-import, dedupe, and source auditing can be
 * implemented without coupling the core module to AndroidX Health Connect.
 */
data class ImportedSleepRecord(
    val record: SleepRecord,
    val sourceRecordId: String?,
    val sourcePackageName: String?,
    val sourceDevice: String? = null,
)

internal fun SleepSessionRecord.toImportedSleepRecord(
    fallbackZoneId: ZoneId,
): ImportedSleepRecord {
    val duration = Duration.between(startTime, endTime)
    val mappedStages = stages.mapNotNull { stage ->
        stage.toSleepStageInterval()
    }
    val stageMinutes = mappedStages
        .groupingBy(SleepStageInterval::level)
        .fold(0) { total, stage -> total + stage.seconds }
        .mapValues { (_, seconds) -> seconds / 60 }
    val wakeMinutes = stageMinutes[SleepStageLevel.WAKE] ?: 0
    val totalMinutes = duration.toMinutes().toInt().coerceAtLeast(0)
    val asleepMinutes = if (mappedStages.isEmpty()) {
        totalMinutes
    } else {
        (totalMinutes - wakeMinutes).coerceAtLeast(0)
    }
    val lightMinutes = if (mappedStages.isEmpty()) {
        asleepMinutes
    } else {
        stageMinutes[SleepStageLevel.LIGHT] ?: 0
    }
    val startOffset = startZoneOffset ?: fallbackZoneId.rules.getOffset(startTime)
    val endOffset = endZoneOffset ?: fallbackZoneId.rules.getOffset(endTime)
    val raw = SleepRecord(
        logId = stableLogId(metadata.id, startTime.toEpochMilli(), endTime.toEpochMilli()),
        dateOfSleep = startTime.atOffset(startOffset).toLocalDate(),
        startTime = startTime,
        endTime = endTime,
        durationMs = duration.toMillis(),
        durationHours = duration.toMillis() / 3_600_000.0,
        efficiency = if (totalMinutes > 0) asleepMinutes * 100 / totalMinutes else 0,
        minutesAsleep = asleepMinutes,
        minutesAwake = wakeMinutes,
        isMainSleep = duration >= Duration.ofHours(MAIN_SLEEP_MINIMUM_HOURS),
        stages = SleepStages(
            deep = stageMinutes[SleepStageLevel.DEEP] ?: 0,
            light = lightMinutes,
            rem = stageMinutes[SleepStageLevel.REM] ?: 0,
            wake = wakeMinutes,
        ),
        stageData = mappedStages,
        startZoneOffset = startOffset,
        endZoneOffset = endOffset,
    )
    return ImportedSleepRecord(
        record = raw.copy(sleepScore = calculateSleepScore(raw)),
        sourceRecordId = metadata.id.ifBlank { null },
        sourcePackageName = metadata.dataOrigin.packageName.ifBlank { null },
        sourceDevice = listOfNotNull(metadata.device?.manufacturer, metadata.device?.model)
            .joinToString(" ")
            .ifBlank { null },
    )
}

private fun SleepSessionRecord.Stage.toSleepStageInterval(): SleepStageInterval? {
    val level = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        -> SleepStageLevel.WAKE
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageLevel.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageLevel.REM
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_SLEEPING,
        SleepSessionRecord.STAGE_TYPE_UNKNOWN,
        -> SleepStageLevel.LIGHT
        else -> return null
    }
    return SleepStageInterval(
        startTime = startTime,
        level = level,
        seconds = Duration.between(startTime, endTime).seconds.toInt(),
    )
}

private fun stableLogId(sourceId: String, startMillis: Long, endMillis: Long): Long {
    val identity = sourceId.ifBlank { "$startMillis:$endMillis" }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    return ByteBuffer.wrap(digest).long and Long.MAX_VALUE
}

private const val MAIN_SLEEP_MINIMUM_HOURS = 4L
