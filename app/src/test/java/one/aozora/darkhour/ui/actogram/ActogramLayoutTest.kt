package one.aozora.darkhour.ui.actogram

import androidx.compose.ui.geometry.Offset
import one.aozora.darkhour.core.circadian.CircadianConfidence
import one.aozora.darkhour.core.circadian.CircadianDay
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.ui.ActogramOrder
import one.aozora.darkhour.ui.ActogramDisplayOptions
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActogramLayoutTest {
    private val offset = ZoneOffset.UTC

    @Test
    fun emptyDataStillProducesStableRows() {
        val layout = ActogramLayoutEngine.build(
            records = emptyList(),
            referenceDate = LocalDate.parse("2026-06-15"),
        )

        assertEquals(7, layout.rows.size)
        assertEquals(LocalDate.parse("2026-06-09"), layout.rows.first().date)
        assertEquals(LocalDate.parse("2026-06-15"), layout.rows.last().date)
        assertFalse(layout.hasRealData)
    }

    @Test
    fun oneRecordIsPaddedToMinimumRows() {
        val record = record(LocalDate.parse("2026-06-15"), 23.0, 7.0)
        val layout = ActogramLayoutEngine.build(listOf(record))

        assertEquals(7, layout.rows.size)
        assertEquals(1, layout.rows.last().sleeps.size)
        assertTrue(layout.hasRealData)
    }

    @Test
    fun thirtyDaysRemainThirtyRows() {
        val first = LocalDate.parse("2026-05-17")
        val records = (0 until 30).map { day -> record(first.plusDays(day.toLong()), 1.0, 8.0) }

        val layout = ActogramLayoutEngine.build(records)

        assertEquals(30, layout.rows.size)
    }

    @Test
    fun sparseHistoryIncludesEmptyDates() {
        val first = LocalDate.parse("2026-05-01")
        val layout = ActogramLayoutEngine.build(
            records = listOf(
                record(first, 1.0, 8.0),
                record(first.plusDays(29), 1.0, 7.0),
            ),
        )

        assertEquals(30, layout.rows.size)
        assertTrue(layout.rows.drop(1).dropLast(1).all { it.sleeps.isEmpty() })
    }

    @Test
    fun longHistoryKeepsAllRows() {
        val first = LocalDate.parse("2026-01-01")
        val records = (0 until 120).map { day -> record(first.plusDays(day.toLong()), 1.0, 8.0) }

        assertEquals(120, ActogramLayoutEngine.build(records).rows.size)
    }

    @Test
    fun sleepCrossingMidnightIsClippedIntoTwoRows() {
        val date = LocalDate.parse("2026-06-14")
        val record = record(date, 22.0, 8.0)
        val layout = ActogramLayoutEngine.build(listOf(record), minimumRows = 2)

        val firstBlock = layout.rows[0].sleeps.single()
        val secondBlock = layout.rows[1].sleeps.single()
        assertEquals(22.0, firstBlock.startHour, 0.001)
        assertEquals(24.0, firstBlock.endHour, 0.001)
        assertEquals(0.0, secondBlock.startHour, 0.001)
        assertEquals(6.0, secondBlock.endHour, 0.001)
    }

    @Test
    fun detailedStagesAreClippedWithSession() {
        val date = LocalDate.parse("2026-06-15")
        val base = date.atStartOfDay().toInstant(offset)
        val record = record(date, 1.0, 4.0).copy(
            stageData = listOf(
                SleepStageInterval(base.plusSeconds(3_600), SleepStageLevel.LIGHT, 3_600),
                SleepStageInterval(base.plusSeconds(7_200), SleepStageLevel.DEEP, 3_600),
            ),
        )

        val block = ActogramLayoutEngine.build(listOf(record), minimumRows = 1).rows.single().sleeps.single()

        assertEquals(2, block.stages.size)
        assertEquals(SleepStageLevel.DEEP, block.stages.last().level)
        assertEquals(60, block.selection.stages.light)
        assertEquals(60, block.selection.stages.deep)
    }

    @Test
    fun customRowWidthAdvancesRowOriginAndRealignsSleep() {
        val firstDate = LocalDate.parse("2026-06-01")
        val records = listOf(
            record(firstDate, 23.0, 7.0),
            record(firstDate.plusDays(1), 23.0, 7.0),
        )

        val layout = ActogramLayoutEngine.build(records, rowHours = 24.5, minimumRows = 2)

        assertEquals(24.5, Duration.between(layout.rows[0].startTime, layout.rows[1].startTime).toMinutes() / 60.0, 0.001)
        assertEquals(23.0, layout.rows[0].sleeps.single().startHour, 0.001)
        assertTrue(layout.rows[1].sleeps.any { kotlin.math.abs(it.startHour - 22.5) < 0.001 })
        assertTrue(layout.rows[1].label.contains(":"))
    }

    @Test
    fun newestFirstFillsViewportWithOlderDatedRows() {
        val layout = ActogramLayoutEngine.build(
            records = listOf(record(LocalDate.parse("2026-06-15"), 23.0, 7.0)),
            minimumRows = 2,
        )

        val rows = layout.rowsForDisplay(ActogramOrder.NEWEST_FIRST, minimumRows = 5)

        assertEquals(5, rows.size)
        assertEquals(LocalDate.parse("2026-06-16"), rows[0].date)
        assertEquals(LocalDate.parse("2026-06-12"), rows[4].date)
        assertTrue(rows.last().sleeps.isEmpty())
    }

    @Test
    fun oldestFirstFillsViewportWithNewerDatedRows() {
        val layout = ActogramLayoutEngine.build(
            records = listOf(record(LocalDate.parse("2026-06-15"), 23.0, 7.0)),
            minimumRows = 2,
        )

        val rows = layout.rowsForDisplay(ActogramOrder.OLDEST_FIRST, minimumRows = 5)

        assertEquals(5, rows.size)
        assertEquals(LocalDate.parse("2026-06-15"), rows[0].date)
        assertEquals(LocalDate.parse("2026-06-19"), rows[4].date)
        assertTrue(rows.last().sleeps.isEmpty())
    }

    @Test
    fun tauViewportRowsContinueExactRowDuration() {
        val layout = ActogramLayoutEngine.build(
            records = listOf(record(LocalDate.parse("2026-06-15"), 23.0, 7.0)),
            rowHours = 24.5,
            minimumRows = 2,
        )

        val rows = layout.rowsForDisplay(ActogramOrder.OLDEST_FIRST, minimumRows = 4)

        assertEquals(24.5, Duration.between(rows[2].startTime, rows[3].startTime).toMinutes() / 60.0, 0.001)
        assertTrue(rows.last().label.contains(":"))
    }

    @Test
    fun doublePlotHitTestingSelectsNextChronologicalSleepOnRightSide() {
        val layout = ActogramLayoutEngine.build(
            records = listOf(
                record(LocalDate.parse("2026-06-15"), 1.0, 4.0),
                record(LocalDate.parse("2026-06-16"), 1.0, 4.0),
            ),
            minimumRows = 2,
        )
        val rows = layout.rowsForDisplay(ActogramOrder.NEWEST_FIRST, minimumRows = 2)
        val options = ActogramDisplayOptions(
            doublePlot = true,
            order = ActogramOrder.NEWEST_FIRST,
        )

        val left = hitTestActogram(rows, options, 24.0, 1_000f, Offset(116f, 63f), 1f)
        val right = hitTestActogram(rows, options, 24.0, 1_000f, Offset(582f, 63f), 1f)

        assertTrue(left is ActogramSelection.Sleep)
        assertTrue(right is ActogramSelection.Sleep)
        assertEquals(LocalDate.parse("2026-06-15").toEpochDay(), (left as ActogramSelection.Sleep).logId)
        assertEquals(LocalDate.parse("2026-06-16").toEpochDay(), (right as ActogramSelection.Sleep).logId)
    }

    @Test
    fun doublePlotHitTestingSelectsNextChronologicalCircadianOnRightSide() {
        val firstDate = LocalDate.parse("2026-06-15")
        val firstSleep = record(firstDate, 1.0, 4.0)
        val secondSleep = record(firstDate.plusDays(1), 1.0, 4.0)
        val layout = ActogramLayoutEngine.build(
            records = listOf(firstSleep, secondSleep),
            circadianDays = listOf(
                circadian(firstDate, firstSleep, 10.0, 12.0),
                circadian(firstDate.plusDays(1), secondSleep, 1.0, 3.0),
            ),
            minimumRows = 2,
        )
        val rows = layout.rowsForDisplay(ActogramOrder.NEWEST_FIRST, minimumRows = 2)
        val options = ActogramDisplayOptions(
            doublePlot = true,
            order = ActogramOrder.NEWEST_FIRST,
        )

        val right = hitTestActogram(rows, options, 24.0, 1_000f, Offset(570f, 52f), 1f)

        assertTrue(right is ActogramSelection.Circadian)
        assertEquals(firstDate.plusDays(1), (right as ActogramSelection.Circadian).date)
    }

    @Test
    fun circadianOverlayCrossingMidnightIsClippedIntoTwoRowsWithSameSelection() {
        val date = LocalDate.parse("2026-06-15")
        val sleep = record(date, 22.0, 7.0)
        val circadian = circadian(date, sleep, 20.5, 4.25)

        val layout = ActogramLayoutEngine.build(
            records = listOf(sleep),
            circadianDays = listOf(circadian),
            minimumRows = 1,
        )

        val firstSegment = layout.rows.first { it.date == date }.overlays.single()
        val secondSegment = layout.rows.first { it.date == date.plusDays(1) }.overlays.single()

        assertEquals(20.5, firstSegment.startHour, 0.001)
        assertEquals(24.0, firstSegment.endHour, 0.001)
        assertEquals(0.0, secondSegment.startHour, 0.001)
        assertEquals(4.25, secondSegment.endHour, 0.001)
        assertEquals(firstSegment.selection, secondSegment.selection)
    }

    @Test
    fun doublePlotBoundaryHitTestingKeepsWrappedCircadianSelection() {
        val date = LocalDate.parse("2026-06-15")
        val sleep = record(date, 22.0, 7.0)
        val layout = ActogramLayoutEngine.build(
            records = listOf(sleep),
            circadianDays = listOf(circadian(date, sleep, 20.0, 2.0)),
            minimumRows = 2,
        )
        val rows = layout.rowsForDisplay(ActogramOrder.OLDEST_FIRST, minimumRows = 2)
        val options = ActogramDisplayOptions(
            doublePlot = true,
            order = ActogramOrder.OLDEST_FIRST,
        )

        val left = hitTestActogram(rows, options, 24.0, 1_000f, Offset(520f, 32f), 1f)
        val right = hitTestActogram(rows, options, 24.0, 1_000f, Offset(550f, 32f), 1f)

        assertTrue(left is ActogramSelection.Circadian)
        assertTrue(right is ActogramSelection.Circadian)
        assertEquals(left, right)
    }

    @Test
    fun sleepHitTakesPriorityOverCircadianBackground() {
        val date = LocalDate.parse("2026-06-15")
        val sleep = record(date, 2.0, 2.0)
        val circadian = circadian(date, sleep, 1.0, 7.0)
        val layout = ActogramLayoutEngine.build(
            records = listOf(sleep),
            circadianDays = listOf(circadian),
            minimumRows = 1,
        )
        val rows = layout.rowsForDisplay(ActogramOrder.OLDEST_FIRST, minimumRows = 1)
        val options = ActogramDisplayOptions(
            doublePlot = false,
            order = ActogramOrder.OLDEST_FIRST,
        )

        val sleepHit = hitTestActogram(rows, options, 24.0, 1_000f, Offset(174f, 41f), 1f)
        val overlayHit = hitTestActogram(rows, options, 24.0, 1_000f, Offset(271f, 33f), 1f)

        assertTrue(sleepHit is ActogramSelection.Sleep)
        assertTrue(overlayHit is ActogramSelection.Circadian)
    }

    private fun record(date: LocalDate, startHour: Double, durationHours: Double): SleepRecord {
        val dayStart = date.atStartOfDay().toInstant(offset)
        val start = dayStart.plusMillis((startHour * 3_600_000).toLong())
        val end = start.plusMillis((durationHours * 3_600_000).toLong())
        return SleepRecord(
            logId = date.toEpochDay(),
            dateOfSleep = date,
            startTime = start,
            endTime = end,
            durationMs = Duration.between(start, end).toMillis(),
            durationHours = durationHours,
            efficiency = 90,
            minutesAsleep = (durationHours * 54).toInt(),
            minutesAwake = (durationHours * 6).toInt(),
            isMainSleep = true,
            sleepScore = 0.8,
            startZoneOffset = offset,
            endZoneOffset = offset,
        )
    }

    private fun circadian(
        date: LocalDate,
        sleep: SleepRecord,
        nightStartHour: Double,
        nightEndHour: Double,
    ): CircadianDay = CircadianDay(
        date = date,
        nightStartHour = nightStartHour,
        nightEndHour = nightEndHour,
        confidenceScore = 0.8,
        confidence = CircadianConfidence.HIGH,
        localTau = 24.0,
        localDrift = 0.0,
        anchorSleep = sleep,
        isForecast = false,
        isGap = false,
    )
}
