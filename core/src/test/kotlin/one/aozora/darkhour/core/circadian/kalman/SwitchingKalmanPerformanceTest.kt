package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.groundtruth.GroundTruthFixtures
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SwitchingKalmanPerformanceTest {
    @Test
    fun longestPrivateFixtureRemainsInteractive() {
        assumeTrue("Private fixtures are unavailable", GroundTruthFixtures.isAvailable)
        val records = GroundTruthFixtures.loadAll().maxBy { it.records.size }.records
        val current = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.KALMAN_ID)
        val switching = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.SWITCHING_KALMAN_ID)
        val currentValues = CircadianAlgorithmRegistry.resolvedValues(current.id, emptyMap())
        val switchingValues = CircadianAlgorithmRegistry.resolvedValues(switching.id, emptyMap())
        current.analyze(records, 0, currentValues)
        switching.analyze(records, 0, switchingValues)

        val currentMillis = medianMillis(3) { current.analyze(records, 0, currentValues) }
        val switchingMillis = medianMillis(3) { switching.analyze(records, 0, switchingValues) }
        println("SWITCHINGPERF\tcurrent=${currentMillis}ms\tswitching=${switchingMillis}ms")

        assertTrue("switching analysis took ${switchingMillis}ms", switchingMillis < 1_000.0)
        assertTrue(
            "switching ${switchingMillis}ms exceeded 3x current ${currentMillis}ms",
            switchingMillis < currentMillis * 3.0,
        )
    }
}

private inline fun medianMillis(repetitions: Int, operation: () -> Unit): Double =
    List(repetitions) {
        val start = System.nanoTime()
        operation()
        (System.nanoTime() - start) / 1_000_000.0
    }.sorted()[repetitions / 2]
