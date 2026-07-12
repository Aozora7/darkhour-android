package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmDefinition
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.circadian.CircadianNumericParameter
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

internal const val DURATION_SMOOTHING_KEY = "duration_smoothing_sigma"

internal data class GroundTruthTuningConfig(
    val initialSamples: Int,
    val generations: Int,
    val population: Int,
    val threads: Int,
    val seed: Long,
    val windowDays: Int,
) {
    init {
        require(initialSamples > 0)
        require(generations >= 0)
        require(population > 0)
        require(threads > 0)
        require(windowDays >= 2)
    }
}

internal data class DatasetTuningScore(
    val datasetId: String,
    val phaseScore: GroundTruthScore,
    val tauScore: GroundTruthTauDivergence,
    val transitionScore: GroundTruthRegimeTransitionScore = GroundTruthRegimeTransitionScore(0, 0, 0.0, 0.0),
)

internal data class TuningCandidate(
    val evaluation: Int,
    val values: Map<String, Double>,
    val objective: Double,
    val tauRiskMinutesPerDay: Double,
    val meanPhaseErrorHours: Double,
    val datasets: List<DatasetTuningScore>,
)

internal fun tuningObjective(scores: List<DatasetTuningScore>): Triple<Double, Double, Double> {
    require(scores.isNotEmpty())
    val meanDelta = scores.map { it.tauScore.meanAbsoluteDeltaMinutesPerDay }
    val p90 = scores.map { it.tauScore.p90AbsoluteDeltaMinutesPerDay }
    val maximum = scores.map { it.tauScore.maxAbsoluteDeltaMinutesPerDay }
    val significantFraction = scores.map { it.tauScore.significantWindowFraction }
    val meanPhaseError = scores.map { it.phaseScore.meanAbsolutePhaseErrorHours }.average()
    val transitionScores = scores.map(DatasetTuningScore::transitionScore)
        .filter { it.transitions > 0 && it.scoredDays > 0 }
    // All tau terms remain in minutes/day. The significant fraction is
    // scaled by a one-hour mismatch so duration matters even when an episode
    // is shorter than the p90 tail of a multi-year fixture.
    val tauRisk = 0.35 * meanDelta.average() +
        0.25 * p90.average() +
        0.20 * p90.max() +
        0.10 * maximum.max() +
        0.10 * 60.0 * significantFraction.max()
    val transitionRisk = transitionScores.takeIf { it.isNotEmpty() }?.let {
        1.5 * it.map(GroundTruthRegimeTransitionScore::meanAbsoluteMovementErrorHours).average() +
            0.5 * it.map(GroundTruthRegimeTransitionScore::p90AbsoluteMovementErrorHours).average()
    } ?: 0.0
    return Triple(tauRisk + 2.0 * meanPhaseError + 2.0 * transitionRisk, tauRisk, meanPhaseError)
}

internal class GroundTruthCandidateEvaluator(
    private val algorithm: CircadianAlgorithmDefinition,
    private val datasets: List<GroundTruthDataset>,
    private val windowDays: Int,
) {
    private val defaults = CircadianAlgorithmRegistry.resolvedValues(algorithm.id, emptyMap())

    fun evaluate(evaluation: Int, values: Map<String, Double>): TuningCandidate {
        val resolved = defaults + values
        val scores = datasets.map { dataset ->
            val prediction = algorithm.analyze(dataset.records, extraDays = 0, values = resolved)
                .toGroundTruthPrediction()
            val phaseScore = scoreAgainstGroundTruth(prediction, dataset)
            DatasetTuningScore(
                datasetId = dataset.id,
                phaseScore = phaseScore,
                tauScore = scoreTauDivergence(prediction, dataset, windowDays),
                transitionScore = phaseScore.regimeTransitions,
            )
        }
        val (objective, tauRisk, meanPhaseError) = tuningObjective(scores)
        return TuningCandidate(evaluation, values, objective, tauRisk, meanPhaseError, scores)
    }
}

internal class GroundTruthParameterSearch(
    parameters: List<CircadianNumericParameter>,
    private val config: GroundTruthTuningConfig,
    includedParameterKeys: Set<String>? = null,
) {
    private val dimensions = parameters
        .filterNot { it.key == DURATION_SMOOTHING_KEY }
        .filter { includedParameterKeys == null || it.key in includedParameterKeys }
        .map(::SearchDimension)
    private val random = Random(config.seed)

    init {
        require(dimensions.isNotEmpty())
    }

    fun search(evaluate: (Int, Map<String, Double>) -> TuningCandidate): List<TuningCandidate> {
        val executor = Executors.newFixedThreadPool(config.threads)
        var nextEvaluation = 0
        val history = mutableListOf<TuningCandidate>()
        try {
            val defaults = dimensions.associate { it.parameter.key to it.parameter.defaultValue }
            history += evaluate(nextEvaluation++, defaults)

            val initial = latinHypercube(config.initialSamples).map(::decode)
            history += evaluateBatch(executor, initial, nextEvaluation, evaluate)
            nextEvaluation += initial.size

            repeat(config.generations) { generation ->
                val eliteCount = minOf(12, max(2, config.population / 4), history.size)
                val elites = history.sortedBy(TuningCandidate::objective).take(eliteCount)
                val progress = if (config.generations <= 1) 1.0 else generation.toDouble() / (config.generations - 1)
                val sigma = 0.20 * (1.0 - progress) + 0.025 * progress
                val population = List(config.population) { index ->
                    if (index < max(1, config.population / 10)) {
                        decode(List(dimensions.size) { random.nextDouble() })
                    } else {
                        val parent = elites[random.nextInt(elites.size)]
                        val normalized = dimensions.map { dimension ->
                            (dimension.encode(parent.values.getValue(dimension.parameter.key)) + random.nextGaussian() * sigma)
                                .coerceIn(0.0, 1.0)
                        }
                        decode(normalized)
                    }
                }
                history += evaluateBatch(executor, population, nextEvaluation, evaluate)
                nextEvaluation += population.size
                val best = history.minBy(TuningCandidate::objective)
                println(
                    "TUNEPROGRESS\tgeneration=${generation + 1}/${config.generations}" +
                        "\tevaluations=${history.size}\tobjective=${format(best.objective)}" +
                        "\ttau-risk=${format(best.tauRiskMinutesPerDay)}min/d" +
                        "\tphase=${format(best.meanPhaseErrorHours)}h",
                )
            }
        } finally {
            executor.shutdown()
        }
        return history.sortedBy(TuningCandidate::objective)
    }

    private fun latinHypercube(samples: Int): List<List<Double>> {
        val byDimension = dimensions.map {
            MutableList(samples) { index -> (index + random.nextDouble()) / samples }.also(::shuffle)
        }
        return List(samples) { sample -> byDimension.map { it[sample] } }
    }

    private fun shuffle(values: MutableList<Double>) {
        for (index in values.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val value = values[index]
            values[index] = values[other]
            values[other] = value
        }
    }

    private fun decode(normalized: List<Double>): Map<String, Double> =
        dimensions.mapIndexed { index, dimension -> dimension.parameter.key to dimension.decode(normalized[index]) }.toMap()

    private fun evaluateBatch(
        executor: java.util.concurrent.ExecutorService,
        values: List<Map<String, Double>>,
        firstEvaluation: Int,
        evaluate: (Int, Map<String, Double>) -> TuningCandidate,
    ): List<TuningCandidate> = executor.invokeAll(
        values.mapIndexed { index, candidate -> Callable { evaluate(firstEvaluation + index, candidate) } },
    ).map { it.get() }
}

internal data class SearchDimension(val parameter: CircadianNumericParameter) {
    private val logarithmic = parameter.minValue > 0.0 &&
        (parameter.key.contains("noise") || parameter.key.contains("variance"))

    fun decode(normalized: Double): Double {
        val raw = if (logarithmic) {
            exp(ln(parameter.minValue) + normalized * (ln(parameter.maxValue) - ln(parameter.minValue)))
        } else {
            parameter.minValue + normalized * (parameter.maxValue - parameter.minValue)
        }
        val scale = 10.0.pow(parameter.decimalPlaces)
        return (round(raw * scale) / scale).coerceIn(parameter.minValue, parameter.maxValue)
    }

    fun encode(value: Double): Double = if (logarithmic) {
        (ln(value) - ln(parameter.minValue)) / (ln(parameter.maxValue) - ln(parameter.minValue))
    } else {
        (value - parameter.minValue) / (parameter.maxValue - parameter.minValue)
    }
}

private fun format(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)
