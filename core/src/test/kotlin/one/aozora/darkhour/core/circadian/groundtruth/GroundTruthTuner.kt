package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmDefinition
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Opt-in parameter search, run with:
 *
 * `./gradlew :core:groundTruthTune -PtuneAlgorithms=all`
 *
 * Duration smoothing remains fixed. Search controls are exposed as the
 * tuneSamples, tuneGenerations, tunePopulation, tuneThreads, tuneSeed, and
 * tuneWindowDays Gradle properties. Reports are written below
 * core/build/reports/ground-truth-tuning.
 */
object GroundTruthTuner {
    @JvmStatic
    fun main(args: Array<String>) {
        check(GroundTruthFixtures.isAvailable) {
            "Private ground-truth fixtures are not available on the test runtime classpath."
        }
        val options = args.associate { argument ->
            val (key, value) = argument.removePrefix("--").split('=', limit = 2)
            key to value
        }
        val algorithmIds = options.getValue("algorithms").let { value ->
            if (value == "all") CircadianAlgorithmRegistry.algorithms.map { it.id } else value.split(',')
        }
        val config = GroundTruthTuningConfig(
            initialSamples = options.getValue("samples").toInt(),
            generations = options.getValue("generations").toInt(),
            population = options.getValue("population").toInt(),
            threads = options.getValue("threads").toInt(),
            seed = options.getValue("seed").toLong(),
            windowDays = options.getValue("window-days").toInt(),
        )
        val outputRoot = Path.of(options.getValue("output"))
        val includedParameterKeys = options["parameters"]
            ?.takeIf(String::isNotBlank)
            ?.split(',')
            ?.toSet()
        val datasets = GroundTruthFixtures.loadAll()
        Files.createDirectories(outputRoot)

        algorithmIds.forEachIndexed { index, algorithmId ->
            val algorithm = CircadianAlgorithmRegistry.algorithm(algorithmId)
            require(algorithm.id == algorithmId) { "Unknown algorithm: $algorithmId" }
            println(
                "TUNESTART\talgorithm=${algorithm.id}\tdatasets=${datasets.size}" +
                    "\twindow=${config.windowDays}d\tseed=${config.seed + index}",
            )
            val evaluator = GroundTruthCandidateEvaluator(algorithm, datasets, config.windowDays)
            val algorithmConfig = config.copy(seed = config.seed + index)
            includedParameterKeys?.let { requested ->
                val available = algorithm.parameters.map { it.key }.toSet()
                require(requested.all { it in available }) {
                    "Unknown parameters for ${algorithm.id}: ${requested - available}"
                }
            }
            val candidates = GroundTruthParameterSearch(
                algorithm.parameters,
                algorithmConfig,
                includedParameterKeys,
            )
                .search(evaluator::evaluate)
            val output = outputRoot.resolve(algorithm.id)
            writeReports(output, algorithm, candidates, algorithmConfig)
            printResult(algorithm, candidates)
        }
    }
}

private fun writeReports(
    output: Path,
    algorithm: CircadianAlgorithmDefinition,
    candidates: List<TuningCandidate>,
    config: GroundTruthTuningConfig,
) {
    Files.createDirectories(output)
    val searchedKeys = candidates.first().values.keys
    val parameterKeys = algorithm.parameters.map { it.key }.filter { it in searchedKeys }
    Files.newBufferedWriter(output.resolve("candidates.csv")).use { writer ->
        writer.appendLine(
            (listOf("rank", "evaluation", "objective", "tauRiskMinutesPerDay", "meanPhaseErrorHours") + parameterKeys)
                .joinToString(","),
        )
        candidates.forEachIndexed { rank, candidate ->
            writer.appendLine(
                (listOf(
                    rank + 1,
                    candidate.evaluation,
                    candidate.objective,
                    candidate.tauRiskMinutesPerDay,
                    candidate.meanPhaseErrorHours,
                ) + parameterKeys.map(candidate.values::getValue)).joinToString(","),
            )
        }
    }
    Files.newBufferedWriter(output.resolve("comparison.csv")).use { writer ->
        writer.appendLine(
            "candidate,dataset,pairedDays,phaseMeanHours,phaseP90Hours,tauMeanMinutesPerDay," +
                "tauP90MinutesPerDay,tauMaxMinutesPerDay,significantWindowFraction,rollingWindows," +
                "regimeTransitions,transitionScoredDays,transitionMovementMeanHours,transitionMovementP90Hours",
        )
        listOf("baseline" to candidates.single { it.evaluation == 0 }, "best" to candidates.first()).forEach {
                (candidateName, candidate) ->
            candidate.datasets.forEach { score ->
                writer.appendLine(
                    listOf(
                        candidateName,
                        csv(score.datasetId),
                        score.phaseScore.pairedDays,
                        score.phaseScore.meanAbsolutePhaseErrorHours,
                        score.phaseScore.p90AbsolutePhaseErrorHours,
                        score.tauScore.meanAbsoluteDeltaMinutesPerDay,
                        score.tauScore.p90AbsoluteDeltaMinutesPerDay,
                        score.tauScore.maxAbsoluteDeltaMinutesPerDay,
                        score.tauScore.significantWindowFraction,
                        score.tauScore.windows,
                        score.transitionScore.transitions,
                        score.transitionScore.scoredDays,
                        score.transitionScore.meanAbsoluteMovementErrorHours,
                        score.transitionScore.p90AbsoluteMovementErrorHours,
                    ).joinToString(","),
                )
            }
        }
    }
    Files.newBufferedWriter(output.resolve("run.txt")).use { writer ->
        writer.appendLine("algorithm=${algorithm.id}")
        writer.appendLine("initialSamples=${config.initialSamples}")
        writer.appendLine("generations=${config.generations}")
        writer.appendLine("population=${config.population}")
        writer.appendLine("threads=${config.threads}")
        writer.appendLine("seed=${config.seed}")
        writer.appendLine("windowDays=${config.windowDays}")
        writer.appendLine("durationSmoothing=excluded")
    }
}

private fun printResult(algorithm: CircadianAlgorithmDefinition, candidates: List<TuningCandidate>) {
    val baseline = candidates.single { it.evaluation == 0 }
    val best = candidates.first()
    println(
        "TUNERESULT\talgorithm=${algorithm.id}\tevaluations=${candidates.size}" +
            "\tobjective=${format(baseline.objective)}->${format(best.objective)}" +
            "\ttau-risk=${format(baseline.tauRiskMinutesPerDay)}->${format(best.tauRiskMinutesPerDay)}min/d" +
            "\tphase=${format(baseline.meanPhaseErrorHours)}->${format(best.meanPhaseErrorHours)}h",
    )
    best.values.forEach { (key, value) -> println("TUNEPARAM\t$key=${format(value, 8)}") }
    best.datasets.forEach { score ->
        println(
            "TUNEDATASET\t${score.datasetId}" +
                "\ttau-p90=${format(score.tauScore.p90AbsoluteDeltaMinutesPerDay)}min/d" +
                "\ttau-max=${format(score.tauScore.maxAbsoluteDeltaMinutesPerDay)}min/d" +
                "\tsignificant=${format(score.tauScore.significantWindowFraction * 100.0)}%" +
                "\ttransitions=${score.transitionScore.transitions}" +
                "\ttransition=${format(score.transitionScore.meanAbsoluteMovementErrorHours)}h" +
                "\tphase=${format(score.phaseScore.meanAbsolutePhaseErrorHours)}h",
        )
    }
}

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
private fun format(value: Double, places: Int = 4): String =
    String.format(Locale.ROOT, "%.${places}f", value)
