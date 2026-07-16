package one.aozora.darkhour.ui

import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DarkHourStateCalculationsTest {
    @Test
    fun napPreferenceFiltersOnlyNonMainSleepRecords() {
        val main = sleepRecord(1, "2026-01-01T00:00:00Z", isMainSleep = true)
        val nap = sleepRecord(2, "2026-01-01T12:00:00Z", isMainSleep = false)

        assertEquals(listOf(main, nap), listOf(main, nap).filteredByNapPreference(true))
        assertEquals(listOf(main), listOf(main, nap).filteredByNapPreference(false))
    }

    @Test
    fun developerDisplayRecordsKeepAllRecordsInTimeOrder() {
        val existing = sleepRecord(1, "2026-01-02T00:00:00Z")
        val injected = sleepRecord(2, "2026-01-01T00:00:00Z")

        assertEquals(
            listOf(injected, existing),
            listOf(existing).withDeveloperDisplayRecords(listOf(injected), isDebug = true),
        )
    }

    @Test
    fun developerAnalysisRecordsDeduplicateByLogId() {
        val existing = sleepRecord(1, "2026-01-02T00:00:00Z")
        val duplicate = sleepRecord(1, "2026-01-01T00:00:00Z")
        val injected = sleepRecord(2, "2026-01-03T00:00:00Z")

        assertEquals(
            listOf(existing, injected),
            listOf(existing).withDeveloperAnalysisRecords(
                listOf(duplicate, injected),
                isDebug = true,
            ),
        )
        assertEquals(
            listOf(existing),
            listOf(existing).withDeveloperAnalysisRecords(listOf(injected), isDebug = false),
        )
    }

    @Test
    fun actogramForecastAddsOneDayOnlyWhenForecastingIsEnabled() {
        assertEquals(0, actogramForecastDays(0))
        assertEquals(0, actogramForecastDays(-1))
        assertEquals(8, actogramForecastDays(7))
    }

    @Test
    fun actogramRowHoursFollowSelectedTimeScale() {
        assertEquals(24.0, actogramRowHours(ActogramDisplayOptions(), globalTau = 25.2), 0.0)
        assertEquals(
            25.2,
            actogramRowHours(
                ActogramDisplayOptions(timeScale = ActogramTimeScale.CIRCADIAN_TAU),
                globalTau = 25.2,
            ),
            0.0,
        )
        assertEquals(
            23.5,
            actogramRowHours(
                ActogramDisplayOptions(
                    timeScale = ActogramTimeScale.CUSTOM,
                    customHours = 23.5f,
                ),
                globalTau = 25.2,
            ),
            0.0,
        )
    }

    private fun sleepRecord(
        id: Long,
        startText: String,
        isMainSleep: Boolean = true,
    ): SleepRecord {
        val start = Instant.parse(startText)
        val end = start.plusSeconds(8 * 3_600)
        return SleepRecord(
            logId = id,
            dateOfSleep = LocalDate.ofInstant(start, ZoneOffset.UTC),
            startTime = start,
            endTime = end,
            durationMs = 8 * 3_600_000L,
            durationHours = 8.0,
            efficiency = 90,
            minutesAsleep = 432,
            minutesAwake = 48,
            isMainSleep = isMainSleep,
        )
    }
}
