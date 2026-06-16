package one.aozora.darkhour.ui.actogram

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActogramTimeFormattingTest {
    private val instant = Instant.parse("2026-06-07T17:23:45Z")

    @Test
    fun twelveHourDateTimeKeepsMonthCapitalizedAndOmitsSeconds() {
        val formatted = formatActogramDateTime(
            instant = instant,
            offset = ZoneOffset.UTC,
            use24HourTime = false,
            locale = Locale.US,
        )

        assertEquals("Jun 7, 2026 5:23 PM", formatted)
        assertFalse(formatted.contains(":45"))
    }

    @Test
    fun twentyFourHourDateTimeOmitsSeconds() {
        assertEquals(
            "Jun 7, 2026 17:23",
            formatActogramDateTime(
                instant = instant,
                offset = ZoneOffset.UTC,
                use24HourTime = true,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun isoDateTimeForcesTwentyFourHourShape() {
        assertEquals(
            "2026-06-07 17:23",
            formatActogramDateTime(
                instant = instant,
                offset = ZoneOffset.UTC,
                use24HourTime = false,
                useIsoDateTime = true,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "2026-06-07",
            formatActogramDate(java.time.LocalDate.parse("2026-06-07"), true, Locale.US),
        )
        assertEquals(
            "2026-06-07 17:23",
            formatActogramRowLabel(
                instant = instant,
                offset = ZoneOffset.UTC,
                use24HourTime = false,
                useIsoDateTime = true,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun axisLabelsFollowClockPreference() {
        assertEquals("00", formatActogramAxisHour(0, true))
        assertEquals("12 AM", formatActogramAxisHour(0, false))
        assertEquals("6 PM", formatActogramAxisHour(18, false))
    }

    @Test
    fun dateLabelWidthBoundsAccountForFullDatesAndTauTimes() {
        assertEquals(92f, actogramMaxLabelWidthDp(true, false, false))
        assertEquals(78f, actogramMaxLabelWidthDp(true, false, true))
        assertEquals(136f, actogramMaxLabelWidthDp(true, true, false))
        assertEquals(122f, actogramMaxLabelWidthDp(true, true, true))
        assertEquals(48f, actogramMinLabelWidthDp(true, false))
        assertEquals(64f, actogramMinLabelWidthDp(true, true))
    }

    @Test
    fun labelTextShrinksWithRowsAndStopsAtPreferredSize() {
        assertEquals(6f, actogramLabelTextSizePx(12f, 1f, false))
        assertEquals(10f, actogramLabelTextSizePx(40f, 1f, false))
        assertEquals(8f, actogramLabelTextSizePx(40f, 1f, true))
    }
}
