package one.aozora.darkhour.core.circadian.csf

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class CsfEndEdgeContinuityTest {
    @Test
    fun irregularBiphasicTailDoesNotFlipToTheWrongCircadianCycle() {
        val firstDate = LocalDate.parse("2026-07-15")
        val stable = (0..18).map { day ->
            sleepAtMidpoint(
                date = firstDate.plusDays(day.toLong()),
                midpointHour = 6.5 + day * 0.18,
                weight = 0.8,
            )
        }
        val irregularTail = listOf(
            sleepAtMidpoint(firstDate.plusDays(21), midpointHour = 6.75, weight = 0.10),
            sleepAtMidpoint(firstDate.plusDays(22), midpointHour = 21.88, weight = 0.11),
            sleepAtMidpoint(firstDate.plusDays(24), midpointHour = 13.82, weight = 0.75),
            sleepAtMidpoint(firstDate.plusDays(25), midpointHour = 13.37, weight = 0.84),
        )

        val analysis = analyzeCircadianCsf(stable + irregularTail, extraDays = 7)
        val recentObserved = analysis.days
            .filter { !it.isForecast && it.date >= firstDate.plusDays(18) }
        val recentMidpoints = recentObserved.map(::midpoint)
        val largestStep = recentMidpoints.zipWithNext { previous, current ->
            abs(circularDiff(current, previous))
        }.max()
        val finalMidpoint = midpoint(recentObserved.last())

        assertTrue("recent midpoints=$recentMidpoints", largestStep < 4.0)
        assertTrue(
            "Expected final midpoint $finalMidpoint near the recent 13.5 h observations",
            abs(circularDiff(finalMidpoint, 13.5)) < 3.0,
        )
    }
}

private fun sleepAtMidpoint(
    date: LocalDate,
    midpointHour: Double,
    weight: Double,
) = run {
    val midpoint = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        .plusMillis((midpointHour * 3_600_000.0).toLong())
    val start = midpoint.minus(Duration.ofHours(4))
    val end = midpoint.plus(Duration.ofHours(4))
    makeSleepRecord(
        logId = date.toEpochDay(),
        dateOfSleep = date,
        startTime = start,
        endTime = end,
        durationMs = Duration.between(start, end).toMillis(),
        durationHours = 8.0,
        sleepScore = weight,
        startZoneOffset = ZoneOffset.UTC,
        endZoneOffset = ZoneOffset.UTC,
    )
}

private fun midpoint(day: one.aozora.darkhour.core.circadian.CircadianDay): Double =
    (day.nightStartHour + day.nightEndHour) / 2.0
