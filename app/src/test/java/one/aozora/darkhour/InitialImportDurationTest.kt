package one.aozora.darkhour

import one.aozora.darkhour.ui.actogram.ActogramDisplayOptions
import one.aozora.darkhour.ui.actogram.ActogramTimeScale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class InitialImportDurationTest {
    @Test
    fun viewportAlwaysImportsAtLeastOneDay() {
        assertEquals(
            Duration.ofDays(1),
            initialVisibleImportDuration(
                viewportHeightDp = 30f,
                options = ActogramDisplayOptions(),
            ),
        )
    }

    @Test
    fun doublePlotIncludesTheAdditionalVisibleRow() {
        assertEquals(
            Duration.ofDays(21),
            initialVisibleImportDuration(
                viewportHeightDp = 470f,
                options = ActogramDisplayOptions(doublePlot = true),
            ),
        )
    }

    @Test
    fun customTimeScaleConvertsVisibleRowsToWholeDays() {
        assertEquals(
            Duration.ofDays(3),
            initialVisibleImportDuration(
                viewportHeightDp = 52f,
                options = ActogramDisplayOptions(
                    doublePlot = true,
                    timeScale = ActogramTimeScale.CUSTOM,
                    customHours = 25f,
                ),
            ),
        )
    }
}
