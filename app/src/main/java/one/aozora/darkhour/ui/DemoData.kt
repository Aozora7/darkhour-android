package one.aozora.darkhour.ui

import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.core.model.calculateSleepScore
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin

object DemoData {
    val records: List<SleepRecord> = buildList {
        val firstDate = LocalDate.now().minusDays(29)
        val offset = ZoneOffset.ofHours(2)

        for (day in 0 until 30) {
            if (day == 8 || day == 19) continue

            val date = firstDate.plusDays(day.toLong())
            val midpoint = 3.2 + day * 0.42 + sin(day * 0.7) * 0.45
            val durationHours = 7.2 + sin(day * 0.43) * 0.8
            val startHour = midpoint - durationHours / 2.0
            val start = date.atStartOfDay().toInstant(offset)
                .plusMillis((startHour * 3_600_000.0).toLong())
            val end = start.plusMillis((durationHours * 3_600_000.0).toLong())
            val deep = (72 + sin(day * 0.9) * 16).toInt()
            val rem = (92 + sin(day * 0.55) * 18).toInt()
            val wake = (34 + sin(day * 0.3) * 10).toInt().coerceAtLeast(8)
            val asleep = (Duration.between(start, end).toMinutes().toInt() - wake).coerceAtLeast(1)
            val light = (asleep - deep - rem).coerceAtLeast(1)
            val stageData = buildStageIntervals(start, end)

            val raw = SleepRecord(
                logId = 10_000L + day,
                dateOfSleep = date,
                startTime = start,
                endTime = end,
                durationMs = Duration.between(start, end).toMillis(),
                durationHours = durationHours,
                efficiency = ((asleep.toDouble() / Duration.between(start, end).toMinutes()) * 100).toInt(),
                minutesAsleep = asleep,
                minutesAwake = wake,
                isMainSleep = true,
                stages = SleepStages(deep = deep, light = light, rem = rem, wake = wake),
                stageData = stageData,
                startZoneOffset = offset,
                endZoneOffset = offset,
            )
            add(raw.copy(sleepScore = calculateSleepScore(raw)))

            if (day % 7 == 3) {
                val napStart = date.atTime(15, 10).toInstant(offset)
                val napEnd = napStart.plusSeconds(70 * 60L)
                add(
                    SleepRecord(
                        logId = 20_000L + day,
                        dateOfSleep = date,
                        startTime = napStart,
                        endTime = napEnd,
                        durationMs = Duration.between(napStart, napEnd).toMillis(),
                        durationHours = 70.0 / 60.0,
                        efficiency = 88,
                        minutesAsleep = 62,
                        minutesAwake = 8,
                        isMainSleep = false,
                        sleepScore = 0.56,
                        startZoneOffset = offset,
                        endZoneOffset = offset,
                    ),
                )
            }
        }
    }.sortedBy { it.startTime }

    private fun buildStageIntervals(
        start: java.time.Instant,
        end: java.time.Instant,
    ): List<SleepStageInterval> {
        val stages = listOf(
            SleepStageLevel.LIGHT,
            SleepStageLevel.DEEP,
            SleepStageLevel.LIGHT,
            SleepStageLevel.REM,
            SleepStageLevel.LIGHT,
            SleepStageLevel.DEEP,
            SleepStageLevel.REM,
            SleepStageLevel.LIGHT,
        )
        val totalSeconds = Duration.between(start, end).seconds
        val intervalSeconds = (totalSeconds / stages.size).toInt()
        return stages.mapIndexed { index, level ->
            SleepStageInterval(
                startTime = start.plusSeconds(index * intervalSeconds.toLong()),
                level = level,
                seconds = if (index == stages.lastIndex) {
                    (totalSeconds - index * intervalSeconds).toInt()
                } else {
                    intervalSeconds
                },
            )
        }
    }
}
