package one.aozora.darkhour.ui.actogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class ActogramGestureTrackerTest {
    @Test
    fun zoomStartDoesNotJump() {
        val tracker = tracker(initialScrollPx = 640f, initialRowHeightDp = 22f)

        val update = tracker.onTransformFrame(
            pointerCount = 2,
            focalY = 220f,
            zoomDelta = 1.20f,
            panY = 18f,
        )

        assertFalse(update.updatesScroll)
        assertTrue(update.frame.reanchored)
        assertFalse(update.frame.updatesRowHeight)
        assertEquals(22f, update.frame.rowHeightDp, UnitTolerance)
        assertEquals(640f, update.frame.scrollOffsetPx, UnitTolerance)
        printGestureDiagnostics(
            testName = "zoomStartDoesNotJump",
            assertions = listOf(
                "first two-pointer frame reanchors",
                "first two-pointer frame does not update scroll",
                "first two-pointer frame does not update row height",
                "frame stays inside row-height and scroll bounds",
            ),
            frames = listOf(update.frame),
        )
        assertFrameInBounds(update.frame)
    }

    @Test
    fun zoomFramesKeepFocalContentRowStable() {
        val tracker = tracker(initialScrollPx = 1_200f, initialRowHeightDp = 22f)
        val frames = mutableListOf<ActogramGestureFrame>()
        frames += tracker.onTransformFrame(2, focalY = 260f, zoomDelta = 1f, panY = 0f).frame

        repeat(40) {
            frames += tracker.onTransformFrame(2, focalY = 260f, zoomDelta = 1.006f, panY = 0f).frame
        }

        printGestureDiagnostics(
            testName = "zoomFramesKeepFocalContentRowStable",
            assertions = listOf(
                "row height stays inside 12dp..60dp",
                "scroll stays inside 0..maxScroll",
                "focal content row equals the gesture anchor on every transform frame",
                "scroll deltas match the zoom anchor equation",
            ),
            frames = frames,
        )
        assertContinuousFrames(frames)
        val anchor = frames.first().anchorRow
        frames.drop(1).forEach { frame ->
            assertEquals(anchor, frame.focalContentRow, UnitTolerance)
        }
    }

    @Test
    fun zoomAndPanTogetherMovesContinuously() {
        val tracker = tracker(initialScrollPx = 1_400f, initialRowHeightDp = 28f)
        val frames = mutableListOf<ActogramGestureFrame>()
        frames += tracker.onTransformFrame(2, focalY = 360f, zoomDelta = 1f, panY = 0f).frame

        repeat(32) {
            frames += tracker.onTransformFrame(2, focalY = 360f, zoomDelta = 1.001f, panY = 5f).frame
        }

        printGestureDiagnostics(
            testName = "zoomAndPanTogetherMovesContinuously",
            assertions = listOf(
                "row height stays inside 12dp..60dp",
                "scroll stays inside 0..maxScroll",
                "combined pan and zoom keeps the focal content row anchored",
                "scroll direction does not unexpectedly reverse",
            ),
            frames = frames,
        )
        assertContinuousFrames(frames)
        val deltas = frames.zipWithNext { previous, current ->
            current.scrollOffsetPx - previous.scrollOffsetPx
        }.drop(1)
        assertTrue(deltas.all { it < 0f })
        frames.drop(1).forEach { frame ->
            assertEquals(frame.anchorRow, frame.focalContentRow, UnitTolerance)
        }
    }

    @Test
    fun zoomOutClampDoesNotOvershoot() {
        val tracker = tracker(initialScrollPx = 116_000f, initialRowHeightDp = 60f)
        val frames = mutableListOf<ActogramGestureFrame>()
        frames += tracker.onTransformFrame(2, focalY = 520f, zoomDelta = 1f, panY = 0f).frame

        repeat(80) {
            frames += tracker.onTransformFrame(2, focalY = 520f, zoomDelta = 0.97f, panY = 0f).frame
        }

        printGestureDiagnostics(
            testName = "zoomOutClampDoesNotOvershoot",
            assertions = listOf(
                "row height clamps at 12dp",
                "scroll never exceeds the shrinking max scroll",
                "max scroll decreases monotonically while zooming out",
                "scroll does not bounce upward during zoom-out clamp",
            ),
            frames = frames,
        )
        assertContinuousFrames(frames)
        frames.forEach(::assertFrameInBounds)
        assertEquals(ActogramGestureLimits.MinRowHeightDp, frames.last().rowHeightDp, UnitTolerance)
        frames.zipWithNext().forEach { (previous, current) ->
            assertTrue(current.maxScrollOffsetPx <= previous.maxScrollOffsetPx + UnitTolerance)
            assertTrue(current.scrollOffsetPx <= previous.scrollOffsetPx + UnitTolerance)
        }
    }

    @Test
    fun pointerCountChangeReanchorsWithoutApplyingStaleZoom() {
        val tracker = tracker(initialScrollPx = 900f, initialRowHeightDp = 22f)
        val start = tracker.onTransformFrame(2, focalY = 240f, zoomDelta = 1f, panY = 0f).frame
        val zoomed = tracker.onTransformFrame(2, focalY = 240f, zoomDelta = 1.15f, panY = 0f).frame
        val reanchored = tracker.onTransformFrame(3, focalY = 260f, zoomDelta = 1.18f, panY = 12f).frame
        val afterReanchor = tracker.onTransformFrame(3, focalY = 260f, zoomDelta = 1.02f, panY = 0f).frame
        val frames = listOf(start, zoomed, reanchored, afterReanchor)

        printGestureDiagnostics(
            testName = "pointerCountChangeReanchorsWithoutApplyingStaleZoom",
            assertions = listOf(
                "pointer-count change produces a reanchor frame",
                "reanchor frame does not apply stale zoom or pan",
                "next frame after reanchor resumes zoom from the new anchor",
                "post-reanchor focal content row matches the new anchor",
            ),
            frames = frames,
        )
        assertTrue(start.reanchored)
        assertFalse(zoomed.reanchored)
        assertTrue(reanchored.reanchored)
        assertEquals(zoomed.rowHeightDp, reanchored.rowHeightDp, UnitTolerance)
        assertEquals(zoomed.scrollOffsetPx, reanchored.scrollOffsetPx, UnitTolerance)
        assertFalse(reanchored.updatesRowHeight)
        assertTrue(afterReanchor.rowHeightDp > reanchored.rowHeightDp)
        assertEquals(reanchored.rowHeightDp * 1.02f, afterReanchor.rowHeightDp, UnitTolerance)
        assertEquals(afterReanchor.anchorRow, afterReanchor.focalContentRow, UnitTolerance)
    }

    @Test
    fun rowHeightClampFramesRemainContinuous() {
        val zoomIn = tracker(initialScrollPx = 1_000f, initialRowHeightDp = 58f)
        val zoomInFrames = mutableListOf<ActogramGestureFrame>()
        zoomInFrames += zoomIn.onTransformFrame(2, focalY = 260f, zoomDelta = 1f, panY = 0f).frame
        repeat(12) {
            zoomInFrames += zoomIn.onTransformFrame(2, focalY = 260f, zoomDelta = 1.08f, panY = 0f).frame
        }

        val zoomOut = tracker(initialScrollPx = 1_000f, initialRowHeightDp = 13f)
        val zoomOutFrames = mutableListOf<ActogramGestureFrame>()
        zoomOutFrames += zoomOut.onTransformFrame(2, focalY = 260f, zoomDelta = 1f, panY = 0f).frame
        repeat(12) {
            zoomOutFrames += zoomOut.onTransformFrame(2, focalY = 260f, zoomDelta = 0.92f, panY = 0f).frame
        }

        printGestureDiagnostics(
            testName = "rowHeightClampFramesRemainContinuous.zoomIn",
            assertions = listOf(
                "zoom-in frames remain continuous",
                "row height clamps at 60dp",
                "scroll stays inside 0..maxScroll",
                "scroll deltas match the zoom anchor equation",
            ),
            frames = zoomInFrames,
        )
        printGestureDiagnostics(
            testName = "rowHeightClampFramesRemainContinuous.zoomOut",
            assertions = listOf(
                "zoom-out frames remain continuous",
                "row height clamps at 12dp",
                "scroll stays inside 0..maxScroll",
                "scroll deltas match the zoom anchor equation",
            ),
            frames = zoomOutFrames,
        )
        assertContinuousFrames(zoomInFrames)
        assertContinuousFrames(zoomOutFrames)
        assertEquals(ActogramGestureLimits.MaxRowHeightDp, zoomInFrames.last().rowHeightDp, UnitTolerance)
        assertEquals(ActogramGestureLimits.MinRowHeightDp, zoomOutFrames.last().rowHeightDp, UnitTolerance)
        (zoomInFrames + zoomOutFrames).forEach(::assertFrameInBounds)
    }

    private fun tracker(
        initialScrollPx: Float,
        initialRowHeightDp: Float,
    ) = ActogramGestureTracker(
        initialScrollPx = initialScrollPx,
        initialRowHeightDp = initialRowHeightDp,
        density = Density,
        axisHeightPx = AxisHeightPx,
        viewportHeightPx = ViewportHeightPx,
        minimumHeightPx = MinimumHeightPx,
        realRowCount = RecordCount,
    )

    private fun assertContinuousFrames(frames: List<ActogramGestureFrame>) {
        frames.forEach(::assertFrameInBounds)
        frames.zipWithNext().forEach { (previous, current) ->
            if (!current.reanchored && current.scrollOffsetPx !in 0f..1f &&
                current.scrollOffsetPx < current.maxScrollOffsetPx - 1f
            ) {
                assertEquals(
                    "focal content row should stay anchored",
                    current.anchorRow,
                    current.focalContentRow,
                    UnitTolerance,
                )
            }
            assertTrue(
                "row-height jump from ${previous.rowHeightDp} to ${current.rowHeightDp}",
                abs(current.rowHeightDp - previous.rowHeightDp) <= 5f ||
                    current.rowHeightDp == ActogramGestureLimits.MinRowHeightDp ||
                    current.rowHeightDp == ActogramGestureLimits.MaxRowHeightDp,
            )
            assertTrue(
                "scroll changed outside anchor equation from $previous to $current",
                scrollMatchesAnchorEquation(previous, current, EquationTolerance),
            )
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

    private fun assertFrameInBounds(frame: ActogramGestureFrame) {
        assertTrue(frame.rowHeightDp in ActogramGestureLimits.MinRowHeightDp..ActogramGestureLimits.MaxRowHeightDp)
        assertTrue(frame.scrollOffsetPx >= -UnitTolerance)
        assertTrue(frame.scrollOffsetPx <= frame.maxScrollOffsetPx + UnitTolerance)
    }

    private fun printGestureDiagnostics(
        testName: String,
        assertions: List<String>,
        frames: List<ActogramGestureFrame>,
    ) {
        if (System.getProperty("darkhour.gestureFrames") != "true") return

        println()
        println("Gesture diagnostics: $testName")
        println("Assertions checked before frame table:")
        assertions.forEachIndexed { index, assertion ->
            println("${index + 1}. $assertion")
        }
        println(frameTable(frames))
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

    private fun Float.fmt(decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", this)

    private companion object {
        const val Density = 1f
        const val AxisHeightPx = 30f
        const val ViewportHeightPx = 640f
        const val MinimumHeightPx = 240f
        const val RecordCount = 2_000
        const val UnitTolerance = 0.001f
        const val EquationTolerance = 0.05f
    }
}
