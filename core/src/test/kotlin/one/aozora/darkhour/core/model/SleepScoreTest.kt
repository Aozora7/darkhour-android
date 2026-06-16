package one.aozora.darkhour.core.model

import one.aozora.darkhour.core.circadian.csf.assertClose
import one.aozora.darkhour.core.circadian.csf.makeSleepRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScoreTest {
    private fun makeRecord(
        minutesAsleep: Int = 420,
        minutesAwake: Int = 30,
        durationMs: Long = 8L * 3_600_000L,
        stages: SleepStages? = SleepStages(deep = 80, light = 200, rem = 100, wake = 40),
        stageData: List<SleepStageInterval> = emptyList(),
    ) = makeSleepRecord(
        minutesAsleep = minutesAsleep,
        minutesAwake = minutesAwake,
        durationMs = durationMs,
        durationHours = durationMs / 3_600_000.0,
        stages = stages,
        stageData = stageData,
    )

    @Test
    fun returnsValueInZeroToOneRange() {
        val score = calculateSleepScore(makeRecord())

        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun shortSleepScoresLowerThanNormalSleep() {
        val short = calculateSleepScore(makeRecord(minutesAsleep = 120, durationMs = (2.5 * 3_600_000).toLong()))
        val normal = calculateSleepScore(makeRecord())

        assertTrue(short < normal)
    }

    @Test
    fun veryLongSleepScoresLowerThanOptimalSleep() {
        val veryLong = calculateSleepScore(makeRecord(minutesAsleep = 780, durationMs = 14L * 3_600_000L))
        val normal = calculateSleepScore(makeRecord())

        assertTrue(veryLong < normal)
        assertTrue(veryLong < 0.85)
    }

    @Test
    fun optimalDurationScoresHigherThanFourHours() {
        val at7h = calculateSleepScore(makeRecord(minutesAsleep = 420))
        val at8h = calculateSleepScore(makeRecord(minutesAsleep = 480))
        val at4h = calculateSleepScore(makeRecord(minutesAsleep = 240))

        assertTrue(at7h > at4h)
        assertTrue(at8h > at4h)
    }

    @Test
    fun highWakePercentageLowersScore() {
        val lowWake = calculateSleepScore(makeRecord(stages = SleepStages(deep = 80, light = 200, rem = 100, wake = 20)))
        val highWake = calculateSleepScore(makeRecord(stages = SleepStages(deep = 80, light = 200, rem = 100, wake = 180)))

        assertTrue(lowWake > highWake)
    }

    @Test
    fun handlesClassicRecordsWithoutStages() {
        val score = calculateSleepScore(makeRecord(stages = null, minutesAsleep = 420, minutesAwake = 30))

        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun clampsExtremeScoreToZeroToOneRange() {
        val extreme = calculateSleepScore(
            makeRecord(
                minutesAsleep = 0,
                minutesAwake = 1,
                durationMs = 60_000,
                stages = SleepStages(deep = 0, light = 0, rem = 0, wake = 1),
            ),
        )

        assertTrue(extreme in 0.0..1.0)
    }

    @Test
    fun keepsDetailedStageIntervalsForFutureVisualization() {
        val intervals = listOf(
            SleepStageInterval(Instant.parse("2024-03-15T23:00:00Z"), SleepStageLevel.LIGHT, 1_800),
            SleepStageInterval(Instant.parse("2024-03-15T23:30:00Z"), SleepStageLevel.DEEP, 1_200),
            SleepStageInterval(Instant.parse("2024-03-15T23:50:00Z"), SleepStageLevel.REM, 900),
        )
        val record = makeRecord(stageData = intervals)

        assertEquals(intervals, record.stageData)
        assertClose(calculateSleepScore(record), calculateSleepScore(record.copy(stageData = emptyList())))
    }
}
