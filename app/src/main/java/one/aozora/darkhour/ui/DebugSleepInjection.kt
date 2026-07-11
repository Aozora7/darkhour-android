package one.aozora.darkhour.ui

import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToLong

data class DebugSleepInjectionForm(
    val date: String,
    val timeFrom: String,
    val timeTo: String,
    val driftMinutesPerDay: String = "0",
    val numberOfDays: String = "1",
)

internal data class DebugSleepInjectionResult(
    val records: List<SleepRecord>,
    val nextForm: DebugSleepInjectionForm,
)

internal fun defaultDebugSleepInjectionForm(
    records: List<SleepRecord>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DebugSleepInjectionForm {
    val latest = records.maxByOrNull(SleepRecord::startTime)
    val date = latest?.dateOfSleep?.plusDays(1) ?: LocalDate.now(zoneId)
    val from = latest?.let { record ->
        record.startTime.atOffset(record.startZoneOffset ?: zoneId.rules.getOffset(record.startTime)).toLocalTime()
    } ?: LocalTime.of(23, 0)
    val normalizedFrom = from.withSecond(0).withNano(0)
    return DebugSleepInjectionForm(
        date = date.toString(),
        timeFrom = normalizedFrom.toMinuteText(),
        timeTo = normalizedFrom.plusHours(DEFAULT_INJECTED_SLEEP_HOURS).toMinuteText(),
    )
}

internal fun generateDebugSleepRecords(
    form: DebugSleepInjectionForm,
    existingInjectedCount: Int,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Result<DebugSleepInjectionResult> = runCatching {
    val firstDate = LocalDate.parse(form.date.trim())
    val firstFrom = parseTime(form.timeFrom)
    val firstTo = parseTime(form.timeTo)
    val driftMinutes = form.driftMinutesPerDay.trim().toDouble()
    require(driftMinutes.isFinite()) { "Drift must be finite" }
    val numberOfDays = form.numberOfDays.trim().toInt()
    require(numberOfDays in 1..MAX_INJECTED_DAYS_PER_ADD) {
        "Number of days must be between 1 and $MAX_INJECTED_DAYS_PER_ADD"
    }
    val driftNanos = (driftMinutes * 60_000_000_000.0).roundToLong()
    val firstStart = firstDate.atTime(firstFrom)
    val firstEndDate = if (firstTo.isAfter(firstFrom)) firstDate else firstDate.plusDays(1)
    val firstEnd = firstEndDate.atTime(firstTo)
    val records = (0 until numberOfDays).map { index ->
        val start = firstStart.plusDays(index.toLong()).plusNanos(driftNanos * index)
        val end = firstEnd.plusDays(index.toLong()).plusNanos(driftNanos * index)
        createInjectedSleepRecord(
            startLocal = start,
            endLocal = end,
            logId = DEBUG_INJECTED_LOG_ID_BASE - existingInjectedCount - index.toLong(),
            zoneId = zoneId,
        )
    }
    val nextStart = firstStart.plusDays(numberOfDays.toLong()).plusNanos(driftNanos * numberOfDays)
    val nextEnd = firstEnd.plusDays(numberOfDays.toLong()).plusNanos(driftNanos * numberOfDays)
    DebugSleepInjectionResult(
        records = records,
        nextForm = form.copy(
            date = nextStart.toLocalDate().toString(),
            timeFrom = nextStart.toLocalTime().toMinuteText(),
            timeTo = nextEnd.toLocalTime().toMinuteText(),
        ),
    )
}

private fun createInjectedSleepRecord(
    startLocal: java.time.LocalDateTime,
    endLocal: java.time.LocalDateTime,
    logId: Long,
    zoneId: ZoneId,
): SleepRecord {
    val start = startLocal.atZone(zoneId)
    val end = endLocal.atZone(zoneId)
    val duration = Duration.between(start.toInstant(), end.toInstant())
    require(!duration.isZero && !duration.isNegative) { "Time to must be after time from" }
    val totalMinutes = duration.toMinutes().toInt()
    return SleepRecord(
        logId = logId,
        dateOfSleep = startLocal.toLocalDate(),
        startTime = start.toInstant(),
        endTime = end.toInstant(),
        durationMs = duration.toMillis(),
        durationHours = duration.toMillis() / 3_600_000.0,
        efficiency = 100,
        minutesAsleep = totalMinutes,
        minutesAwake = 0,
        isMainSleep = true,
        sleepScore = 0.95,
        startZoneOffset = start.offset,
        endZoneOffset = end.offset,
    )
}

private fun parseTime(value: String): LocalTime = try {
    LocalTime.parse(value.trim())
} catch (error: Exception) {
    throw IllegalArgumentException("Time must use HH:mm", error)
}

private fun LocalTime.toMinuteText(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

private const val DEFAULT_INJECTED_SLEEP_HOURS = 7L
private const val MAX_INJECTED_DAYS_PER_ADD = 365
private const val DEBUG_INJECTED_LOG_ID_BASE = -8_000_000_000L
