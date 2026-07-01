package one.aozora.darkhour.core.circadian.csf

import one.aozora.darkhour.core.circadian.CircadianAnalyzer
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsfIntegrationTest {
    @Test
    fun detectsStableAndPositiveSyntheticTau() {
        assertAnalysisTau(CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 24.0, days = 90, noise = 0.0))), 24.0, 0.15)
        assertAnalysisTau(CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 90, noise = 0.0))), 24.5, 0.15)
        assertAnalysisTau(CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 25.0, days = 90, noise = 0.0))), 25.0, 0.2)
    }

    @Test
    fun toleratesNoisyDataAndRandomGaps() {
        val noisy = CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 120, noise = 0.8, seed = 7)))
        assertTrue(kotlin.math.abs(noisy.globalTau - 24.5) < 0.6)

        val gappy = CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 120, noise = 0.3, gapFraction = 0.3, seed = 11)))
        assertTrue(kotlin.math.abs(gappy.globalTau - 24.5) < 0.7)
    }

    @Test
    fun producesForecastDaysWithDecayingConfidence() {
        val analysis = CircadianAnalyzer.analyze(
            generateSyntheticRecords(SyntheticOptions(tau = 24.5, days = 30, noise = 0.0)),
            extraDays = 7,
        )

        val forecast = analysis.days.filter { it.isForecast }
        assertEquals(7, forecast.size)
        assertTrue(forecast.first().confidenceScore > forecast.last().confidenceScore)
        assertTrue(forecast.all { !it.isGap })
    }

    @Test
    fun usesRecordLocalMidnightAsCircadianDayOrigin() {
        val offset = ZoneOffset.ofHours(2)
        val first = LocalDate.parse("2026-06-01")
        val records = (0 until 30).map { day ->
            val date = first.plusDays(day.toLong())
            val start = date.atStartOfDay().toInstant(offset).plus(Duration.ofHours(8))
            val end = start.plus(Duration.ofHours(8))
            makeSleepRecord(
                logId = day.toLong(),
                dateOfSleep = date,
                startTime = start,
                endTime = end,
                durationMs = Duration.between(start, end).toMillis(),
                durationHours = 8.0,
                sleepScore = 0.9,
                startZoneOffset = offset,
                endZoneOffset = offset,
            )
        }

        val analysis = CircadianAnalyzer.analyze(records)
        val mids = analysis.days.map { (it.nightStartHour + it.nightEndHour) / 2.0 }

        assertTrue(mids.isNotEmpty())
        assertTrue("midpoints=$mids", mids.all { kotlin.math.abs(it - 12.0) < 0.5 })
    }

    @Test
    fun marksLargeGapsAndKeepsSegmentsIsolated() {
        val first = generateSyntheticRecords(SyntheticOptions(tau = 24.0, days = 30, noise = 0.0))
        val second = generateSyntheticRecords(SyntheticOptions(tau = 25.0, days = 30, noise = 0.0))
            .map { record ->
                val shiftedDate = record.dateOfSleep.plusDays(60)
                val deltaMs = 60L * 86_400_000L
                record.copy(
                    logId = record.logId + 10_000,
                    dateOfSleep = shiftedDate,
                    startTime = record.startTime.plusMillis(deltaMs),
                    endTime = record.endTime.plusMillis(deltaMs),
                )
            }

        val analysis = CircadianAnalyzer.analyze(first + second)

        assertTrue(analysis.days.any { it.isGap })
        val postGap = analysis.days.filter { !it.isGap && it.date >= second.first().dateOfSleep }
        assertFalse(postGap.isEmpty())
        assertTrue(postGap.takeLast(10).map { it.localTau }.average() > 24.4)
    }

    @Test
    fun forecastsOnlyFromLatestSegmentAfterLargeGap() {
        val first = generateSyntheticRecords(SyntheticOptions(tau = 24.0, days = 30, noise = 0.0))
        val gapDays = 20L
        val second = generateSyntheticRecords(SyntheticOptions(tau = 25.0, days = 30, noise = 0.0))
            .map { record ->
                val shiftedDate = record.dateOfSleep.plusDays(gapDays)
                val deltaMs = gapDays * 86_400_000L
                record.copy(
                    logId = record.logId + 10_000,
                    dateOfSleep = shiftedDate,
                    startTime = record.startTime.plusMillis(deltaMs),
                    endTime = record.endTime.plusMillis(deltaMs),
                )
            }

        val analysis = CircadianAnalyzer.analyze(first + second, extraDays = 30)
        val forecastDates = analysis.days.filter { it.isForecast }.map { it.date }
        val secondSegmentDates = second.map { it.dateOfSleep }.toSet()

        assertEquals(30, forecastDates.size)
        assertTrue(forecastDates.none { it in secondSegmentDates })
        assertEquals(forecastDates.size, forecastDates.toSet().size)
        assertTrue(forecastDates.first() > second.last().dateOfSleep)
    }

    @Test
    fun detectsNegativeDrift() {
        val analysis = CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(tau = 23.5, days = 120, noise = 0.0)))

        assertTrue(kotlin.math.abs(analysis.globalTau - 23.5) < 0.35)
        assertTrue(analysis.globalDailyDrift < 0.0)
    }

    @Test
    fun handlesFragmentationAndNapContamination() {
        val fragmented = CircadianAnalyzer.analyze(
            generateSyntheticRecords(
                SyntheticOptions(
                    tau = 24.5,
                    days = 120,
                    noise = 0.2,
                    napFraction = 0.5,
                    fragmentedPeriods = listOf(FragmentedPeriod(startDay = 40, endDay = 70, boutsPerDay = 3, boutDuration = 3.5)),
                    seed = 99,
                ),
            ),
        )

        assertTrue(fragmented.anchorCount > 40)
        assertTrue(fragmented.days.none { !it.localTau.isFinite() || !it.nightStartHour.isFinite() || !it.nightEndHour.isFinite() })
        assertTrue(kotlin.math.abs(fragmented.globalTau - 24.5) < 1.0)
    }

    @Test
    fun returnsSaneDefaultsForEmptyAndSingleRecordInput() {
        val empty = CircadianAnalyzer.analyze(emptyList())
        assertEquals(ALGORITHM_ID, empty.algorithmId)
        assertEquals(24.0, empty.globalTau, 0.0)
        assertTrue(empty.days.isEmpty())

        val single = CircadianAnalyzer.analyze(generateSyntheticRecords(SyntheticOptions(days = 1)))
        assertEquals(24.0, single.globalTau, 0.0)
        assertTrue(single.days.isEmpty())
    }
}
