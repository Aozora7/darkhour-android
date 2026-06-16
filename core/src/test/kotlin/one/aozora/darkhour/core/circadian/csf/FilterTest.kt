package one.aozora.darkhour.core.circadian.csf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    private val emptyRecord = makeSleepRecord()

    @Test
    fun normalizeAngleNormalizesPositiveNegativeAndFractionalValues() {
        assertClose(normalizeAngle(5.0), 5.0)
        assertClose(normalizeAngle(24.0), 0.0)
        assertClose(normalizeAngle(50.0), 2.0)
        assertClose(normalizeAngle(-1.0), 23.0)
        assertClose(normalizeAngle(-24.0), 0.0)
        assertClose(normalizeAngle(-0.5), 23.5)
        assertClose(normalizeAngle(24.5), 0.5)
    }

    @Test
    fun circularDiffUsesShortestPathAcrossMidnight() {
        assertClose(circularDiff(3.0, 1.0), 2.0)
        assertClose(circularDiff(1.0, 3.0), -2.0)
        assertClose(circularDiff(1.0, 23.0), 2.0)
        assertClose(circularDiff(23.0, 1.0), -2.0)
        assertClose(circularDiff(0.0, 24.0), 0.0)
    }

    @Test
    fun resolveAmbiguityUnwrapsToNearestPredictionBranch() {
        assertClose(resolveAmbiguity(5.0, 6.0), 5.0)
        assertClose(resolveAmbiguity(1.0, 23.0), 25.0)
        assertClose(resolveAmbiguity(23.0, 1.0), -1.0)
        assertClose(resolveAmbiguity(5.0, 53.0), 53.0)
        assertClose(resolveAmbiguity(5.0, -43.0), -43.0)
    }

    @Test
    fun vonMisesUpdateHandlesPriorMeasurementBalance() {
        val measurementOnly = vonMisesUpdate(10.0, 0.0, 5.0, 1.0)
        assertClose(measurementOnly.phase, 5.0, 0.0001)
        assertClose(measurementOnly.kappa, 1.0, 0.0001)

        val priorOnly = vonMisesUpdate(10.0, 1.0, 0.0, 0.0)
        assertClose(priorOnly.phase, 10.0, 0.0001)
        assertClose(priorOnly.kappa, 1.0, 0.0001)

        val equal = vonMisesUpdate(0.0, 1.0, 2.0, 1.0)
        assertClose(equal.phase, 1.0, 0.0001)
        assertClose(equal.kappa, 2.0 * kotlin.math.cos(Math.PI / 12.0), 0.0001)

        assertClose(vonMisesUpdate(10.0, 1.0, 5.0, 10.0).phase, 5.36, 0.1)
        assertClose(vonMisesUpdate(10.0, 10.0, 5.0, 1.0).phase, 9.64, 0.1)
        assertClose(vonMisesUpdate(5.0, 1.0, 5.0, 1.0).kappa, 2.0, 0.0001)
    }

    @Test
    fun initializeStateUsesFirstAnchorAndConfig() {
        val anchor = CsfAnchor(dayNumber = 0, midpointHour = 3.0, weight = 1.0, record = emptyRecord)
        val state = initializeState(anchor, CsfConfig.Default)

        assertClose(state.phase, 3.0)
        assertClose(state.tau, CsfConfig.Default.tauPrior)
        assertClose(state.tauVar, CsfConfig.Default.tauPriorVar)
        assertClose(state.phaseVar, 1.0)
        assertClose(state.cov, 0.0)
    }

    @Test
    fun predictAdvancesPhaseAndUncertainty() {
        val state = CsfState(phase = 10.0, tau = 25.0, phaseVar = 0.5, tauVar = 0.1, cov = 0.05)
        val predicted = predict(state, CsfConfig.Default)

        assertClose(predicted.phase, 11.0)
        assertClose(predict(state.copy(tau = 24.0), CsfConfig.Default).phase, 10.0)
        assertClose(predict(state.copy(tau = 23.0), CsfConfig.Default).phase, 9.0)
        assertClose(predicted.tau, 25.0)
        assertTrue(predicted.phaseVar > state.phaseVar)
        assertTrue(predicted.tauVar > state.tauVar)
        assertTrue(predicted.cov > state.cov)
    }

    @Test
    fun updatePriorPullsTauTowardPriorAndClampsBounds() {
        val config = CsfConfig.Default.copy(
            tauPrior = 24.5,
            tauPriorNoise = TauPriorNoise(forward = 0.1, backward = 1.0, none = 5.0),
        )
        val highTau = CsfState(phase = 10.0, tau = 26.0, phaseVar = 0.5, tauVar = 0.1, cov = 0.05)
        val updated = updatePrior(highTau, config)

        assertTrue(updated.tau < 26.0)
        assertTrue(updated.tau > 24.5)

        val extreme = updatePrior(highTau.copy(tau = 30.0, tauVar = 10.0), config)
        assertTrue(extreme.tau <= TAU_MAX)
        assertTrue(extreme.tau >= TAU_MIN)
        assertTrue(updated.tauVar < highTau.tauVar)
    }

    @Test
    fun updateAcceptsMeasurementsAndWeightsHighConfidenceMore() {
        val predicted = CsfState(phase = 5.0, tau = 25.0, phaseVar = 0.5, tauVar = 0.1, cov = 0.05)
        val accepted = update(
            predicted,
            CsfAnchor(dayNumber = 1, midpointHour = 6.0, weight = 1.0, record = emptyRecord),
            CsfConfig.Default,
        )

        assertNotEquals(predicted.phase, accepted.phase)

        val low = update(predicted, CsfAnchor(1, 10.0, 0.1, emptyRecord), CsfConfig.Default)
        val high = update(predicted, CsfAnchor(1, 10.0, 1.0, emptyRecord), CsfConfig.Default)
        assertTrue(kotlin.math.abs(high.phase - predicted.phase) > kotlin.math.abs(low.phase - predicted.phase))

        val clamped = update(
            CsfState(phase = 5.0, tau = 25.0, phaseVar = 0.01, tauVar = 0.01, cov = 0.1),
            CsfAnchor(1, 20.0, 10.0, emptyRecord),
            CsfConfig.Default,
        )
        assertTrue(clamped.tau in TAU_MIN..TAU_MAX)
    }

    @Test
    fun forwardPassProducesStatePerDayAndConvergesTowardObservedDrift() {
        fun anchor(dayNumber: Int, midpointHour: Double, weight: Double = 1.0) =
            CsfAnchor(dayNumber, midpointHour, weight, emptyRecord)

        assertEquals(3, forwardPass(listOf(anchor(0, 3.0), anchor(1, 4.0), anchor(2, 5.0)), 0, 2, CsfConfig.Default).size)
        assertEquals(6, forwardPass(listOf(anchor(0, 3.0), anchor(5, 8.0)), 0, 5, CsfConfig.Default).size)

        val drifting = forwardPass(listOf(anchor(0, 3.0)), 0, 10, CsfConfig.Default)
        assertClose(drifting[10].phase - drifting[0].phase, (CsfConfig.Default.tauPrior - 24.0) * 10.0, 0.1)

        val anchors = (0 until 60).map { d -> anchor(d, 3.0 + d * 1.0) }
        val states = forwardPass(anchors, 0, 59, CsfConfig.Default)
        assertTrue(kotlin.math.abs(states[59].tau - 25.0) < 0.5)
    }

    @Test
    fun rtsSmootherSmoothsBackwardAndPreservesBounds() {
        fun state(phase: Double, tau: Double) = CsfState(phase, tau, phaseVar = 0.5, tauVar = 0.1, cov = 0.05)

        assertTrue(rtsSmoother(emptyList(), CsfConfig.Default).isEmpty())

        val single = rtsSmoother(listOf(state(5.0, 25.0)), CsfConfig.Default)
        assertClose(single[0].smoothedPhase, 5.0)
        assertClose(single[0].smoothedTau, 25.0)

        val smoothed = rtsSmoother(listOf(state(0.0, 25.0), state(1.0, 25.0), state(10.0, 25.0), state(1.0, 25.0), state(2.0, 25.0)), CsfConfig.Default)
        assertNotEquals(0.0, smoothed[0].smoothedPhase)

        val last = smoothed.last()
        assertClose(last.smoothedPhase, last.phase)

        val varied = rtsSmoother((0 until 30).map { i -> state(i.toDouble(), 25.0 + kotlin.math.sin(i.toDouble()) * 2.0) }, CsfConfig.Default)
        for (s in varied) {
            assertTrue(s.smoothedTau in TAU_MIN..TAU_MAX)
        }

        val stable = listOf(state(0.0, 25.0), state(1.0, 25.0), state(2.0, 25.0), state(3.0, 25.0), state(4.0, 25.0))
        val stableSmoothed = rtsSmoother(stable, CsfConfig.Default)
        for (i in 0 until stableSmoothed.lastIndex) {
            assertTrue(stableSmoothed[i].smoothedPhaseVar < stable[i].phaseVar)
        }
    }
}
