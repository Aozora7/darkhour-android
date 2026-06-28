package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.ui.ActogramDisplayOptions
import one.aozora.darkhour.ui.ActogramOrder
import one.aozora.darkhour.ui.theme.DarkHourTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

class ActogramGestureInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pinchWithLargeHistoryHasNoIntermediateJump() {
        val frames = renderLargeActogram()

        composeRule.onNodeWithTag(CanvasTag).performTouchInput {
            pinch(
                start0 = Offset(center.x - 34f, center.y),
                start1 = Offset(center.x + 34f, center.y),
                end0 = Offset(center.x - 110f, center.y),
                end1 = Offset(center.x + 110f, center.y),
                durationMillis = 900,
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            printGestureDiagnostics(
                testName = "pinchWithLargeHistoryHasNoIntermediateJump",
                assertions = listOf(
                    "row height stays inside 12dp..60dp",
                    "scroll stays inside 0..maxScroll",
                    "pure zoom keeps the original focal content row anchored",
                    "every intermediate frame matches the zoom anchor equation",
                ),
                frames = frames,
            )
            assertStableGestureFrames(frames, requirePureZoomAnchor = true)
        }
    }

    @Test
    fun pinchAndVerticalPanWithLargeHistoryIsContinuous() {
        val frames = renderLargeActogram()

        composeRule.onNodeWithTag(CanvasTag).performTouchInput {
            pinch(
                start0 = Offset(center.x - 42f, center.y - 80f),
                start1 = Offset(center.x + 42f, center.y - 80f),
                end0 = Offset(center.x - 120f, center.y + 100f),
                end1 = Offset(center.x + 120f, center.y + 100f),
                durationMillis = 1_000,
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            printGestureDiagnostics(
                testName = "pinchAndVerticalPanWithLargeHistoryIsContinuous",
                assertions = listOf(
                    "row height stays inside 12dp..60dp",
                    "scroll stays inside 0..maxScroll",
                    "pan plus zoom keeps the current focal content row anchored",
                    "scroll direction does not unexpectedly reverse away from clamps",
                ),
                frames = frames,
            )
            assertStableGestureFrames(frames, requirePureZoomAnchor = false)
            val transformFrames = frames.filterNot { it.reanchored }
            val scrollDeltas = transformFrames.zipWithNext { previous, current ->
                current.scrollOffsetPx - previous.scrollOffsetPx
            }.filter { abs(it) > 0.5f }
            assertTrue("expected scroll movement during pan+zoom", scrollDeltas.isNotEmpty())
            assertTrue(
                "unexpected scroll direction reversal: $scrollDeltas",
                scrollDeltas.zipWithNext().count { (a, b) -> a.sign() != b.sign() } <= 1,
            )
        }
    }

    @Test
    fun zoomCompletionDoesNotCorrectAfterBadFrames() {
        val frames = renderLargeActogram()

        composeRule.onNodeWithTag(CanvasTag).performTouchInput {
            pinch(
                start0 = Offset(center.x - 28f, center.y + 60f),
                start1 = Offset(center.x + 28f, center.y + 60f),
                end0 = Offset(center.x - 96f, center.y - 30f),
                end1 = Offset(center.x + 96f, center.y - 30f),
                durationMillis = 1_100,
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            printGestureDiagnostics(
                testName = "zoomCompletionDoesNotCorrectAfterBadFrames",
                assertions = listOf(
                    "there are multiple intermediate transform frames",
                    "every intermediate frame passes the same continuity checks as the final frame",
                    "the final state is not the only valid frame",
                    "scroll deltas match the zoom anchor equation",
                ),
                frames = frames,
            )
            assertStableGestureFrames(frames, requirePureZoomAnchor = false)
            val transformFrames = frames.filterNot { it.reanchored }
            assertTrue("need intermediate frames, got ${transformFrames.size}", transformFrames.size >= 3)
            val final = transformFrames.last()
            assertTrue(
                "expected valid intermediate frames before final correction",
                transformFrames.dropLast(1).any { abs(final.rowHeightDp - it.rowHeightDp) > 1f },
            )
        }
    }

    private fun renderLargeActogram(): MutableList<ActogramGestureFrame> {
        val layout = largeLayout()
        lateinit var frames: MutableList<ActogramGestureFrame>
        composeRule.setContent {
            frames = remember { mutableStateListOf<ActogramGestureFrame>() }
            var options by remember {
                mutableStateOf(
                    ActogramDisplayOptions(
                        rowHeightDp = 22f,
                        order = ActogramOrder.OLDEST_FIRST,
                    ),
                )
            }
            DarkHourTheme {
                ActogramCanvas(
                    layout = layout,
                    options = options,
                    useIsoDateTime = false,
                    selection = null,
                    onSelectionChange = {},
                    onRowHeightChange = { options = options.copy(rowHeightDp = it) },
                    onTransformingChange = {},
                    modifier = Modifier.fillMaxSize().testTag(CanvasTag),
                    onGestureFrame = { frames.add(it) },
                )
            }
        }
        composeRule.waitForIdle()
        return frames
    }

    private fun assertStableGestureFrames(
        frames: List<ActogramGestureFrame>,
        requirePureZoomAnchor: Boolean,
    ) {
        assertTrue("expected gesture frames", frames.isNotEmpty())
        val transformFrames = frames.filterNot { it.reanchored }
        assertTrue("expected transform frames, got $frames", transformFrames.size >= 2)

        frames.forEach { frame ->
            assertTrue(frame.rowHeightDp in ActogramGestureLimits.MinRowHeightDp..ActogramGestureLimits.MaxRowHeightDp)
            assertTrue("negative scroll: $frame", frame.scrollOffsetPx >= -InstrumentedTolerance)
            assertTrue(
                "scroll beyond max: $frame",
                frame.scrollOffsetPx <= frame.maxScrollOffsetPx + InstrumentedTolerance,
            )
        }

        transformFrames.zipWithNext().forEach { (previous, current) ->
            assertTrue(
                "row-height jump from $previous to $current",
                abs(current.rowHeightDp - previous.rowHeightDp) <= 4f,
            )
            assertTrue(
                "scroll changed outside anchor equation from $previous to $current",
                scrollMatchesAnchorEquation(previous, current, InstrumentedTolerance),
            )
            if (current.scrollOffsetPx > 1f && current.scrollOffsetPx < current.maxScrollOffsetPx - 1f) {
                assertEquals(
                    "focal row should stay close to anchor for $current",
                    current.anchorRow,
                    current.focalContentRow,
                    InstrumentedTolerance,
                )
            }
        }

        if (requirePureZoomAnchor) {
            val anchor = frames.first().anchorRow
            transformFrames.forEach { frame ->
                if (frame.scrollOffsetPx > 1f && frame.scrollOffsetPx < frame.maxScrollOffsetPx - 1f) {
                    assertEquals(anchor, frame.focalContentRow, InstrumentedTolerance)
                }
            }
        }
    }

    private fun scrollMatchesAnchorEquation(
        previous: ActogramGestureFrame,
        current: ActogramGestureFrame,
        tolerance: Float,
    ): Boolean {
        if (current.reanchored) return true
        if (current.scrollOffsetPx <= tolerance || current.scrollOffsetPx >= current.maxScrollOffsetPx - tolerance) {
            return true
        }
        val expectedDelta = current.anchorRow * (current.rowHeightPx - previous.rowHeightPx) -
            (current.focalY - previous.focalY)
        val actualDelta = current.scrollOffsetPx - previous.scrollOffsetPx
        return abs(expectedDelta - actualDelta) <= tolerance
    }

    private fun printGestureDiagnostics(
        testName: String,
        assertions: List<String>,
        frames: List<ActogramGestureFrame>,
    ) {
        if (InstrumentationRegistry.getArguments().getString("gestureFrames") != "true") return

        emitDiagnosticLine("")
        emitDiagnosticLine("Gesture diagnostics: $testName")
        emitDiagnosticLine("Assertions checked before frame table:")
        assertions.forEachIndexed { index, assertion ->
            emitDiagnosticLine("${index + 1}. $assertion")
        }
        frameTable(frames).lineSequence().forEach(::emitDiagnosticLine)
    }

    private fun emitDiagnosticLine(line: String) {
        println(line)
        Log.i(LogTag, line)
    }

    private fun frameTable(frames: List<ActogramGestureFrame>): String {
        val header = "| # | ptr | reanchor | zoomDelta | panY | rowDp | rowPx | scrollPx | maxScrollPx | focalY | anchorRow | focalRow |"
        val separator = "|---:|---:|:---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
        val rows = frames.mapIndexed { index, frame ->
            listOf(
                index.toString(),
                frame.pointerCount.toString(),
                if (frame.reanchored) "yes" else "no",
                frame.zoomDelta.fmt(5),
                frame.panY.fmt(2),
                frame.rowHeightDp.fmt(3),
                frame.rowHeightPx.fmt(3),
                frame.scrollOffsetPx.fmt(3),
                frame.maxScrollOffsetPx.fmt(3),
                frame.focalY.fmt(2),
                frame.anchorRow.fmt(4),
                frame.focalContentRow.fmt(4),
            ).joinToString(prefix = "| ", postfix = " |", separator = " | ")
        }
        return (listOf(header, separator) + rows).joinToString(System.lineSeparator())
    }

    private fun largeLayout(): ActogramLayout =
        ActogramLayoutEngine.build(
            records = syntheticRecords(count = RecordCount),
            minimumRows = RecordCount,
        )

    private fun syntheticRecords(count: Int): List<SleepRecord> {
        val firstDate = LocalDate.parse("2020-01-01")
        val offset = ZoneOffset.UTC
        return (0 until count).map { day ->
            val date = firstDate.plusDays(day.toLong())
            val startMinute = 21 * 60 + 30 + (day * 17 % 180)
            val durationMinutes = 390 + (day * 19 % 120)
            val start = date.atStartOfDay().plusMinutes(startMinute.toLong()).toInstant(offset)
            val end = start.plusSeconds(durationMinutes * 60L)
            val stageData = if (day % 5 == 0) {
                listOf(
                    SleepStageInterval(start, SleepStageLevel.LIGHT, durationMinutes * 20),
                    SleepStageInterval(start.plusSeconds(durationMinutes * 20L), SleepStageLevel.DEEP, durationMinutes * 12),
                    SleepStageInterval(start.plusSeconds(durationMinutes * 32L), SleepStageLevel.REM, durationMinutes * 10),
                )
            } else {
                emptyList()
            }
            SleepRecord(
                logId = day.toLong(),
                dateOfSleep = date,
                startTime = start,
                endTime = end,
                durationMs = Duration.between(start, end).toMillis(),
                durationHours = durationMinutes / 60.0,
                efficiency = 90,
                minutesAsleep = (durationMinutes * 0.9).toInt(),
                minutesAwake = (durationMinutes * 0.1).toInt(),
                isMainSleep = true,
                sleepScore = 0.8,
                stages = if (stageData.isEmpty()) {
                    null
                } else {
                    SleepStages(
                        deep = stageData.filter { it.level == SleepStageLevel.DEEP }.sumOf { it.seconds } / 60,
                        light = stageData.filter { it.level == SleepStageLevel.LIGHT }.sumOf { it.seconds } / 60,
                        rem = stageData.filter { it.level == SleepStageLevel.REM }.sumOf { it.seconds } / 60,
                        wake = 0,
                    )
                },
                stageData = stageData,
                startZoneOffset = offset,
                endZoneOffset = offset,
            )
        }
    }

    private fun Float.sign(): Int = when {
        this > 0f -> 1
        this < 0f -> -1
        else -> 0
    }

    private fun Float.fmt(decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", this)

    private companion object {
        const val CanvasTag = "large_actogram_canvas"
        const val LogTag = "GestureFrames"
        const val RecordCount = 2_000
        const val InstrumentedTolerance = 1f
    }
}
