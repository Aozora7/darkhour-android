package one.aozora.darkhour.core.circadian.csf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {
    @Test
    fun fiveDayTimescalePreservesOriginalOutputAndEdgeHorizons() {
        val smoothing = CsfSmoothingConfig(5.0)

        assertEquals(5.0, smoothing.smoothingDays, 0.0)
        assertEquals(8, smoothing.outputRadiusDays)
        assertEquals(10, smoothing.edgeBlendDays)
        assertEquals(7.0, smoothing.edgeAnchorSigmaDays, 0.0)
        assertEquals(15, smoothing.edgeAnchorRadiusDays)
        assertEquals(30, smoothing.edgeRegressionLookbackDays)
        assertEquals(15, smoothing.startReferenceFirstDay)
        assertEquals(25, smoothing.startReferenceLastDay)
    }

    @Test
    fun widerOutputSmoothingDampensShortTauChanges() {
        val states = listOf(24.0, 24.0, 27.0, 24.0, 24.0).mapIndexed { day, tau ->
            SmoothedState(
                CsfState(
                    phase = day.toDouble(),
                    tau = tau,
                    phaseVar = 0.5,
                    tauVar = 0.1,
                    cov = 0.0,
                ),
            )
        }

        val narrow = smoothOutputStates(
            states,
            CsfSmoothingConfig(smoothingDays = 2.0),
        )
        val wide = smoothOutputStates(
            states,
            CsfSmoothingConfig(smoothingDays = 5.0),
        )

        assertTrue(wide[2].smoothedTau < narrow[2].smoothedTau)
    }

    @Test
    fun endEdgeBlendsTauIntoForecastRegression() {
        val anchors = (0..30).map { day ->
            CsfAnchor(
                dayNumber = day,
                midpointHour = 3.0 + day,
                weight = 1.0,
                record = makeSleepRecord(logId = day.toLong()),
            )
        }
        val states = (0..35).map { day ->
            SmoothedState(
                CsfState(
                    phase = 3.0 + day,
                    tau = 24.0,
                    phaseVar = 0.5,
                    tauVar = 0.1,
                    cov = 0.0,
                ),
            )
        }.toMutableList()

        correctEdges(
            states = states,
            anchors = anchors,
            segFirstDay = 0,
            lastDataLocalDay = 30,
            totalDays = 35,
            smoothing = CsfSmoothingConfig(5.0),
        )

        assertEquals(24.0, states[20].smoothedTau, 1e-9)
        assertTrue(states[25].smoothedTau > 24.0)
        assertTrue(states[25].smoothedTau < 25.0)
        assertEquals(25.0, states[30].smoothedTau, 1e-9)
        assertTrue(states.drop(31).all { kotlin.math.abs(it.smoothedTau - 25.0) < 1e-9 })
    }

    @Test
    fun edgeRegressionExpandsToIncludeMinimumAnchorSupport() {
        val anchors = listOf(0, 10, 20, 30).map { day ->
            CsfAnchor(
                dayNumber = day,
                midpointHour = 3.0 + day * 0.5,
                weight = 1.0,
                record = makeSleepRecord(logId = day.toLong()),
            )
        }
        val states = (0..32).map { day ->
            SmoothedState(CsfState(3.0 + day * 0.5, 24.0, 0.5, 0.1, 0.0))
        }.toMutableList()

        correctEdges(
            states = states,
            anchors = anchors,
            segFirstDay = 0,
            lastDataLocalDay = 30,
            totalDays = 32,
            smoothing = CsfSmoothingConfig(smoothingDays = 2.0),
        )

        assertEquals(24.5, states.last().smoothedTau, 1e-9)
    }
}
