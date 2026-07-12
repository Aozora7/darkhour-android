package one.aozora.darkhour.core.circadian.adaptive

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveKalmanModelTest {
    @Test
    fun stableNoiseDoesNotTurnIntoTauNoiseOrTransitionEvidence() {
        val observations = (0 until 180).map { day ->
            observation(day, 4.0 + 0.8 * day + 1.5 * sin(day * 1.7))
        }

        val states = fitAdaptiveKalmanTrend(observations)
        val central = states.drop(30).dropLast(30)

        assertTrue(central.none { it.transitionEvidence > 0.0 })
        assertTrue(central.maxOf(AdaptiveKalmanState::drift) - central.minOf(AdaptiveKalmanState::drift) < 0.25)
        assertTrue(abs(central.map(AdaptiveKalmanState::drift).average() - 0.8) < 0.1)
    }

    @Test
    fun isolatedPhaseOutlierStaysInObservationNoise() {
        val observations = (0 until 120).map { day ->
            observation(day, 3.0 + 0.7 * day + if (day == 60) 8.0 else 0.0)
        }

        val states = fitAdaptiveKalmanTrend(observations)

        assertTrue(states.none { it.transitionEvidence > 0.0 })
        assertTrue(states.drop(20).dropLast(20).all { abs(it.drift - 0.7) < 0.15 })
    }

    @Test
    fun coherentFreeRunningToEntrainedTransitionActivatesWideProcessComponent() {
        val boundary = 90
        val observations = (0 until 125).map { day ->
            val phase = if (day < boundary) 2.0 + day else 2.0 + boundary
            observation(day, phase + 0.08 * sin(day.toDouble()))
        }

        val states = fitAdaptiveKalmanTrend(observations)
        val evidenceDays = states.filter { it.transitionEvidence > 0.0 }.map(AdaptiveKalmanState::dayNumber)
        val postDrift = states.takeLast(10).map(AdaptiveKalmanState::drift).average()

        assertTrue("no transition evidence", evidenceDays.isNotEmpty())
        assertTrue("evidence days were $evidenceDays", evidenceDays.first() in boundary - 2..boundary + 2)
        assertTrue("post-transition drift was $postDrift", abs(postDrift) < 0.25)
    }

    @Test
    fun gradualTauChangeUsesOrdinaryKalmanDynamics() {
        val observations = (0 until 240).runningFoldIndexed(4.0) { day, phase, _ ->
            phase + 0.4 + 0.6 * day / 239.0
        }.drop(1).mapIndexed { day, phase -> observation(day, phase) }

        val states = fitAdaptiveKalmanTrend(observations)
        val early = states.subList(30, 60).map(AdaptiveKalmanState::drift).average()
        val late = states.takeLast(30).map(AdaptiveKalmanState::drift).average()

        assertTrue(states.none { it.transitionEvidence > 0.0 })
        assertTrue("early=$early late=$late", late - early > 0.20)
    }

    private fun observation(day: Int, phase: Double) = AdaptiveKalmanObservation(
        dayNumber = day,
        midpointHour = phase,
        weight = 1.0,
    )
}
