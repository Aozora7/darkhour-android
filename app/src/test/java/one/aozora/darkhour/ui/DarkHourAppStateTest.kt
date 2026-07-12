package one.aozora.darkhour.ui

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class DarkHourAppStateTest {
    @Test
    fun debugStartsWithSwitchingKalmanButReleaseKeepsProductionDefault() {
        assertEquals(
            CircadianAlgorithmRegistry.KALMAN_ID,
            initialDeveloperAlgorithmId(isDebug = true),
        )
        assertEquals(
            CircadianAlgorithmRegistry.KALMAN_ID,
            initialDeveloperAlgorithmId(isDebug = false),
        )
    }
}
