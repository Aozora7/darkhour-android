package one.aozora.darkhour.core.circadian.adaptive

import java.util.Random
import one.aozora.darkhour.core.circadian.kalman.KalmanChangeDetectionConfig
import one.aozora.darkhour.core.circadian.kalman.KalmanConfig
import one.aozora.darkhour.core.circadian.kalman.KalmanObservation
import one.aozora.darkhour.core.circadian.kalman.detectKalmanChangePoints
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveKalmanTransitionLatencyTest {
    @Test
    fun quantifiesNoisyFreeRunningToEntrainedDetectionLatency() {
        val summary = transitionBenchmark(fromDrift = 1.0, toDrift = 0.0)
        val baseline = legacyTransitionBenchmark(fromDrift = 1.0, toDrift = 0.0)

        println("ADAPTIVE_LATENCY\tfree-to-entrained\t${summary.format()}")
        println("LEGACY_LATENCY\tfree-to-entrained\t${baseline.format()}")
        assertTrue("detection rate was ${summary.detectionRate}", summary.detectionRate >= 0.90)
        assertTrue("commit rate was ${summary.commitRate}", summary.commitRate >= 0.20)
        assertTrue("P90 confirmation latency was ${summary.p90LatencyDays} days", summary.p90LatencyDays <= 18)
        assertTrue("P90 boundary error was ${summary.p90BoundaryErrorDays} days", summary.p90BoundaryErrorDays <= 3)
        assertEquals(0, summary.earlyFalsePositives)
    }

    @Test
    fun quantifiesNoisyEntrainmentToFreeRunningDetectionLatency() {
        val summary = transitionBenchmark(fromDrift = 0.0, toDrift = 1.0)
        val baseline = legacyTransitionBenchmark(fromDrift = 0.0, toDrift = 1.0)

        println("ADAPTIVE_LATENCY\tentrained-to-free\t${summary.format()}")
        println("LEGACY_LATENCY\tentrained-to-free\t${baseline.format()}")
        assertTrue("detection rate was ${summary.detectionRate}", summary.detectionRate >= 0.90)
        assertTrue("commit rate was ${summary.commitRate}", summary.commitRate >= 0.20)
        assertTrue("P90 confirmation latency was ${summary.p90LatencyDays} days", summary.p90LatencyDays <= 18)
        assertTrue("P90 boundary error was ${summary.p90BoundaryErrorDays} days", summary.p90BoundaryErrorDays <= 3)
        assertEquals(0, summary.earlyFalsePositives)
    }

    @Test
    fun quantifiesSparseNoisyTransitionLatency() {
        val summary = sparseTransitionBenchmark(fromDrift = 1.0, toDrift = 0.0)

        println("ADAPTIVE_LATENCY\tsparse-free-to-entrained\t${summary.format()}")
        assertTrue("detection rate was ${summary.detectionRate}", summary.detectionRate >= 0.90)
        assertTrue("commit rate was ${summary.commitRate}", summary.commitRate >= 0.20)
        assertTrue("P90 sparse confirmation latency was ${summary.p90LatencyDays} days", summary.p90LatencyDays <= 18)
        assertTrue("P90 sparse boundary error was ${summary.p90BoundaryErrorDays} days", summary.p90BoundaryErrorDays <= 3)
        assertEquals(0, summary.earlyFalsePositives)
    }

    @Test
    fun stableNoiseOffsetsGradualChangeAndNon24StepDoNotBecomeTransitions() {
        val falseTransitions = mutableListOf<String>()
        repeat(SEEDS) { seed ->
            val stable = syntheticObservations(seed, fromDrift = 0.8, toDrift = 0.8)
            val offsetOnly = syntheticObservations(seed, fromDrift = 0.8, toDrift = 0.8, phaseOffset = 4.0)
            val non24Step = syntheticObservations(seed, fromDrift = 0.65, toDrift = 1.15)
            val gradual = gradualObservations(seed)
            mapOf(
                "stable" to stable,
                "offset" to offsetOnly,
                "non24-step" to non24Step,
                "gradual" to gradual,
            ).forEach { (name, observations) ->
                detectAdaptiveKalmanTransitions(observations).takeIf(List<AdaptiveKalmanTransition>::isNotEmpty)
                    ?.let { falseTransitions += "$name/$seed=$it" }
            }
        }

        assertTrue("false transitions: ${falseTransitions.take(8)}", falseTransitions.isEmpty())
    }

    @Test
    fun lowWeightFragmentationAndMissingDataDoNotManufactureEvidence() {
        repeat(SEEDS) { seed ->
            val fragmented = syntheticObservations(seed, fromDrift = 0.8, toDrift = 0.8).map { observation ->
                if (observation.dayNumber in 75..105) {
                    observation.copy(
                        midpointHour = observation.midpointHour - 0.7 * (observation.dayNumber - 75),
                        weight = 0.25,
                    )
                } else {
                    observation
                }
            }
            val gappy = syntheticObservations(seed, fromDrift = 0.8, toDrift = 0.8)
                .filterNot { it.dayNumber in 75..105 }

            assertTrue(
                "fragmentation seed $seed created ${detectAdaptiveKalmanTransitions(fragmented)}",
                detectAdaptiveKalmanTransitions(fragmented).isEmpty(),
            )
            assertTrue(
                "gap seed $seed created ${detectAdaptiveKalmanTransitions(gappy)}",
                detectAdaptiveKalmanTransitions(gappy).isEmpty(),
            )
        }
    }
}

private data class LatencySummary(
    val runs: Int,
    val detections: Int,
    val commits: Int,
    val latencies: List<Int>,
    val boundaryErrors: List<Int>,
    val earlyFalsePositives: Int,
) {
    val detectionRate: Double get() = detections.toDouble() / runs
    val commitRate: Double get() = commits.toDouble() / runs
    val medianLatencyDays: Int get() = percentile(latencies, 0.50)
    val p90LatencyDays: Int get() = percentile(latencies, 0.90)
    val medianBoundaryErrorDays: Int get() = percentile(boundaryErrors, 0.50)
    val p90BoundaryErrorDays: Int get() = percentile(boundaryErrors, 0.90)

    fun format(): String =
        "runs=$runs\tdetected=$detections\tcommitted=$commits" +
            "\trate=${"%.2f".format(java.util.Locale.ROOT, detectionRate)}" +
            "\tcommit-rate=${"%.2f".format(java.util.Locale.ROOT, commitRate)}" +
            "\tlatency-median=${medianLatencyDays}d\tlatency-p90=${p90LatencyDays}d" +
            "\tboundary-median=${medianBoundaryErrorDays}d\tboundary-p90=${p90BoundaryErrorDays}d" +
            "\tearly-fp=$earlyFalsePositives"
}

private fun transitionBenchmark(fromDrift: Double, toDrift: Double): LatencySummary {
    val latencies = mutableListOf<Int>()
    val boundaryErrors = mutableListOf<Int>()
    var earlyFalsePositives = 0
    var commits = 0
    repeat(SEEDS) { seed ->
        val transitions = detectAdaptiveKalmanTransitions(
            syntheticObservations(seed, fromDrift, toDrift),
        )
        earlyFalsePositives += transitions.count { it.boundaryDay < TRANSITION_DAY - 3 }
        transitions.firstOrNull { it.confirmationDay >= TRANSITION_DAY }?.let { transition ->
            if (transition.committed) commits++
            latencies += transition.confirmationDay - TRANSITION_DAY
            boundaryErrors += abs(transition.boundaryDay - TRANSITION_DAY)
        }
    }
    return LatencySummary(SEEDS, latencies.size, commits, latencies.sorted(), boundaryErrors.sorted(), earlyFalsePositives)
}

private fun legacyTransitionBenchmark(fromDrift: Double, toDrift: Double): LatencySummary {
    val latencies = mutableListOf<Int>()
    val boundaryErrors = mutableListOf<Int>()
    var earlyFalsePositives = 0
    repeat(SEEDS) { seed ->
        val observations = syntheticObservations(seed, fromDrift, toDrift)
        val transitions = detectKalmanChangePoints(
            observations = observations.map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) },
            firstDay = 0,
            lastDay = TOTAL_DAYS - 1,
            config = LEGACY_CONFIG,
        )
        earlyFalsePositives += transitions.count { it.dayNumber < TRANSITION_DAY - 3 }
        transitions.firstOrNull { it.confirmationDayNumber >= TRANSITION_DAY }?.let { transition ->
            latencies += transition.confirmationDayNumber - TRANSITION_DAY
            boundaryErrors += abs(transition.dayNumber - TRANSITION_DAY)
        }
    }
    return LatencySummary(SEEDS, latencies.size, latencies.size, latencies.sorted(), boundaryErrors.sorted(), earlyFalsePositives)
}

private fun sparseTransitionBenchmark(fromDrift: Double, toDrift: Double): LatencySummary {
    val latencies = mutableListOf<Int>()
    val boundaryErrors = mutableListOf<Int>()
    var earlyFalsePositives = 0
    var commits = 0
    repeat(SEEDS) { seed ->
        val observations = syntheticObservations(seed, fromDrift, toDrift)
            .filterNot { it.dayNumber % 3 == 1 }
        val transitions = detectAdaptiveKalmanTransitions(observations)
        earlyFalsePositives += transitions.count { it.boundaryDay < TRANSITION_DAY - 3 }
        transitions.firstOrNull { it.confirmationDay >= TRANSITION_DAY }?.let { transition ->
            if (transition.committed) commits++
            latencies += transition.confirmationDay - TRANSITION_DAY
            boundaryErrors += abs(transition.boundaryDay - TRANSITION_DAY)
        }
    }
    return LatencySummary(SEEDS, latencies.size, commits, latencies.sorted(), boundaryErrors.sorted(), earlyFalsePositives)
}

private fun syntheticObservations(
    seed: Int,
    fromDrift: Double,
    toDrift: Double,
    phaseOffset: Double = 0.0,
): List<AdaptiveKalmanObservation> {
    val random = Random(seed.toLong())
    var phase = 3.0
    return (0 until TOTAL_DAYS).map { day ->
        if (day > 0) phase += if (day <= TRANSITION_DAY) fromDrift else toDrift
        val offset = if (phaseOffset != 0.0 && day >= TRANSITION_DAY) phaseOffset else 0.0
        val ordinaryNoise = random.nextGaussian() * NOISE_STANDARD_DEVIATION_HOURS
        val outlier = if (random.nextDouble() < OUTLIER_FRACTION) random.nextGaussian() * 1.2 else 0.0
        AdaptiveKalmanObservation(
            dayNumber = day,
            midpointHour = phase + offset + ordinaryNoise + outlier,
            weight = 0.75 + random.nextDouble() * 0.25,
        )
    }
}

private fun gradualObservations(seed: Int): List<AdaptiveKalmanObservation> {
    val random = Random(seed.toLong())
    var phase = 3.0
    return (0 until TOTAL_DAYS).map { day ->
        if (day > 0) phase += 0.4 + 0.6 * day / (TOTAL_DAYS - 1.0)
        AdaptiveKalmanObservation(
            dayNumber = day,
            midpointHour = phase + random.nextGaussian() * NOISE_STANDARD_DEVIATION_HOURS,
            weight = 0.75 + random.nextDouble() * 0.25,
        )
    }
}

private fun percentile(sorted: List<Int>, fraction: Double): Int {
    if (sorted.isEmpty()) return Int.MAX_VALUE
    return sorted[((sorted.size - 1) * fraction).toInt()]
}

private const val SEEDS = 24
private const val TOTAL_DAYS = 145
private const val TRANSITION_DAY = 90
private const val NOISE_STANDARD_DEVIATION_HOURS = 0.25
private const val OUTLIER_FRACTION = 0.05

private val LEGACY_CONFIG = KalmanConfig(
    driftPrior = 0.51,
    processPhaseVariance = 0.49,
    processDriftVariance = 0.0001,
    measurementVarianceAtUnitWeight = 10.0,
    changeDetection = KalmanChangeDetectionConfig(
        windowDays = 26,
        minAnchors = 7,
        minAnchorWeight = 0.23,
        minDriftDelta = 0.93,
        fitImprovement = 2.4,
    ),
)
