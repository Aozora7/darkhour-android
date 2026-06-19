package one.aozora.darkhour.ui

import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.core.model.calculateSleepScore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

object DemoData {
    val records: List<SleepRecord> = buildList {
        val firstDate = LocalDate.now().minusDays(62)
        val zone = ZoneId.systemDefault()
        val skippedDays = setOf(11, 32, 47)
        val napDays = setOf(8, 18, 28, 39, 52, 59)

        for (day in 0 until 62) {
            if (day in skippedDays) continue

            val date = firstDate.plusDays(day.toLong())
            val weekend = date.dayOfWeek.value >= 6
            val shortNight = day == 21 || day == 44
            val earlyMorning = day == 36
            val startMinute = when {
                shortNight -> minutesAt(1, 12) + waveMinutes(day, 17)
                weekend -> minutesAt(23, 58) + waveMinutes(day, 22)
                else -> minutesAt(23, 18) + waveMinutes(day, 18)
            }
            val durationMinutes = when {
                shortNight -> 330 + waveMinutes(day, 24)
                earlyMorning -> 405
                weekend -> 500 + waveMinutes(day, 28)
                else -> 455 + waveMinutes(day, 22)
            }.coerceIn(300, 540)
            val start = date.atMinuteOfDay(startMinute).toInstant(zone)
            val end = start.plusSeconds(durationMinutes * 60L)
            val stageData = buildStageIntervals(start, end, day)
            val stages = summarizeStages(stageData)
            val wake = stages.wake
            val asleep = (durationMinutes - wake).coerceAtLeast(1)

            val raw = SleepRecord(
                logId = 10_000L + day,
                dateOfSleep = date,
                startTime = start,
                endTime = end,
                durationMs = Duration.between(start, end).toMillis(),
                durationHours = durationMinutes / 60.0,
                efficiency = ((asleep.toDouble() / durationMinutes) * 100).roundToInt(),
                minutesAsleep = asleep,
                minutesAwake = wake,
                isMainSleep = true,
                stages = stages,
                stageData = stageData,
                startZoneOffset = zone.rules.getOffset(start),
                endZoneOffset = zone.rules.getOffset(end),
            )
            add(raw.copy(sleepScore = calculateSleepScore(raw)))

            if (day in napDays) {
                val napMinutes = if (weekend) 38 else 52
                val napStart = date.atMinuteOfDay(minutesAt(14, 18) + waveMinutes(day, 13)).toInstant(zone)
                val napEnd = napStart.plusSeconds(napMinutes * 60L)
                add(
                    SleepRecord(
                        logId = 20_000L + day,
                        dateOfSleep = date,
                        startTime = napStart,
                        endTime = napEnd,
                        durationMs = Duration.between(napStart, napEnd).toMillis(),
                        durationHours = napMinutes / 60.0,
                        efficiency = 86,
                        minutesAsleep = napMinutes - 7,
                        minutesAwake = 7,
                        isMainSleep = false,
                        sleepScore = 0.56,
                        startZoneOffset = zone.rules.getOffset(napStart),
                        endZoneOffset = zone.rules.getOffset(napEnd),
                    ),
                )
            }
        }
    }.sortedBy { it.startTime }

    private fun LocalDate.atMinuteOfDay(minuteOfDay: Int) =
        atTime(LocalTime.MIDNIGHT).plusMinutes(minuteOfDay.toLong())

    private fun java.time.LocalDateTime.toInstant(zone: ZoneId): Instant =
        atZone(zone).toInstant()

    private fun minutesAt(hour: Int, minute: Int): Int = hour * 60 + minute

    private fun waveMinutes(day: Int, amplitude: Int): Int =
        (cos(day * PI / 5.5) * amplitude).roundToInt()

    private fun buildStageIntervals(
        start: Instant,
        end: Instant,
        day: Int,
    ): List<SleepStageInterval> {
        val deepBias = waveMinutes(day, 5)
        val remBias = waveMinutes(day + 3, 6)
        val template = listOf(
            SleepStageLevel.WAKE to 8,
            SleepStageLevel.LIGHT to 42,
            SleepStageLevel.DEEP to 35 + deepBias,
            SleepStageLevel.LIGHT to 48,
            SleepStageLevel.REM to 18 + remBias,
            SleepStageLevel.WAKE to 4,
            SleepStageLevel.LIGHT to 52,
            SleepStageLevel.DEEP to 25 + deepBias / 2,
            SleepStageLevel.REM to 30 + remBias,
            SleepStageLevel.LIGHT to 50,
            SleepStageLevel.WAKE to 6,
            SleepStageLevel.REM to 36,
            SleepStageLevel.LIGHT to 56,
        )
        val totalMinutes = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(1)
        val templateMinutes = template.sumOf { it.second }
        var cursor = start
        var assigned = 0
        return template.mapIndexed { index, (level, minutes) ->
            val segmentMinutes = if (index == template.lastIndex) {
                totalMinutes - assigned
            } else {
                ((minutes.toDouble() / templateMinutes) * totalMinutes).roundToInt()
                    .coerceAtLeast(1)
            }
            SleepStageInterval(
                startTime = cursor,
                level = level,
                seconds = segmentMinutes * 60,
            ).also {
                cursor = cursor.plusSeconds(segmentMinutes * 60L)
                assigned += segmentMinutes
            }
        }
    }

    private fun summarizeStages(intervals: List<SleepStageInterval>): SleepStages {
        fun minutes(level: SleepStageLevel): Int =
            intervals.filter { it.level == level }.sumOf { it.seconds } / 60

        return SleepStages(
            deep = minutes(SleepStageLevel.DEEP),
            light = minutes(SleepStageLevel.LIGHT),
            rem = minutes(SleepStageLevel.REM),
            wake = minutes(SleepStageLevel.WAKE),
        )
    }
}
