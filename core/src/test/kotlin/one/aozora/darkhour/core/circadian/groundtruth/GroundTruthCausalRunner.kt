package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmDefinition
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Opt-in blocked forecast evaluation, run with `./gradlew :core:groundTruthCausal`. */
object GroundTruthCausalRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        check(GroundTruthFixtures.isAvailable) {
            "Private ground-truth fixtures are not available on the test runtime classpath."
        }
        val options = args.associate { argument ->
            val (key, value) = argument.removePrefix("--").split('=', limit = 2)
            key to value
        }
        val historyDays = options.getValue("history-days").toInt()
        val horizonDays = options.getValue("horizon-days").toInt()
        val spacingDays = options.getValue("spacing-days").toInt()
        val overrides = options.getValue("overrides")
            .split(',')
            .filter(String::isNotBlank)
            .associate { entry ->
                val (key, value) = entry.split('=', limit = 2)
                key to value.toDouble()
            }
        val definitions = options.getValue("algorithms").let { value ->
            if (value == "all") CircadianAlgorithmRegistry.algorithms else value.split(',').map { algorithmId ->
                CircadianAlgorithmRegistry.algorithm(algorithmId).also {
                    require(it.id == algorithmId) { "Unknown algorithm: $algorithmId" }
                }
            }
        }
        val algorithms = definitions.map { definition ->
            val relevantOverrides = overrides.filterKeys { key -> definition.parameters.any { it.key == key } }
            CausalAlgorithmRun(
                id = buildString {
                    append(definition.id)
                    relevantOverrides.toSortedMap().forEach { (key, value) -> append("@$key=$value") }
                },
                definition = definition,
                values = CircadianAlgorithmRegistry.resolvedValues(definition.id, relevantOverrides),
            )
        }
        val recognizedOverrideKeys = definitions.flatMap { it.parameters }.map { it.key }.toSet()
        require(overrides.keys.all { it in recognizedOverrideKeys }) {
            "Unknown parameter override: ${overrides.keys - recognizedOverrideKeys}"
        }
        val datasets = GroundTruthFixtures.loadAll()
        val output = Path.of(options.getValue("output"))
        Files.createDirectories(output)

        val results = algorithms.flatMap { algorithm ->
            datasets.mapNotNull { dataset ->
                evaluateDataset(algorithm, dataset, historyDays, horizonDays, spacingDays)
            }
        }
        writeReports(output, results)
        results.forEach(::printResult)
        algorithms.forEach { algorithm ->
            val scores = results.filter { it.algorithmId == algorithm.id }.flatMap(CausalDatasetResult::scores)
            if (scores.isNotEmpty()) {
                printSummary("CAUSALTOTAL", algorithm.id, summarizeCausalForecasts(scores), results.sumOf { result ->
                    if (result.algorithmId == algorithm.id) result.missingWindows else 0
                })
            }
        }
    }
}

private data class CausalAlgorithmRun(
    val id: String,
    val definition: CircadianAlgorithmDefinition,
    val values: Map<String, Double>,
)

private data class CausalDatasetResult(
    val algorithmId: String,
    val datasetId: String,
    val missingWindows: Int,
    val scores: List<CausalForecastWindowScore>,
)

private fun evaluateDataset(
    algorithm: CausalAlgorithmRun,
    dataset: GroundTruthDataset,
    historyDays: Int,
    horizonDays: Int,
    spacingDays: Int,
): CausalDatasetResult? {
    val cutoffs = causalCutoffs(dataset, historyDays, horizonDays, spacingDays)
    if (cutoffs.isEmpty()) return null
    val scores = cutoffs.mapNotNull { cutoff ->
        val history = historicalRecords(dataset.records, cutoff)
        val prediction = algorithm.definition.analyze(
            records = history,
            extraDays = horizonDays + FORECAST_MARGIN_DAYS,
            values = algorithm.values,
        ).toGroundTruthPrediction()
        scoreCausalForecastWindow(dataset.id, cutoff, horizonDays, prediction, dataset.overlay)
    }
    return CausalDatasetResult(
        algorithmId = algorithm.id,
        datasetId = dataset.id,
        missingWindows = cutoffs.size - scores.size,
        scores = scores,
    )
}

private fun writeReports(output: Path, results: List<CausalDatasetResult>) {
    Files.newBufferedWriter(output.resolve("windows.csv")).use { writer ->
        writer.appendLine(
            "algorithm,dataset,cutoff,horizonDays,circularPhaseMaeHours,unwrappedPhaseMaeHours," +
                "endpointPhaseErrorHours,cumulativeMovementErrorHours,tauDeltaMinutesPerDay",
        )
        results.forEach { result ->
            result.scores.forEach { score ->
                writer.appendLine(
                    listOf(
                        result.algorithmId,
                        csv(result.datasetId),
                        score.cutoff,
                        score.horizonDays,
                        score.circularPhaseMaeHours,
                        score.unwrappedPhaseMaeHours,
                        score.endpointPhaseErrorHours,
                        score.cumulativeMovementErrorHours,
                        score.tauDeltaMinutesPerDay,
                    ).joinToString(","),
                )
            }
        }
    }
    Files.newBufferedWriter(output.resolve("summary.csv")).use { writer ->
        writer.appendLine(
            "algorithm,dataset,windows,missing,circularPhaseMaeHours,unwrappedPhaseMaeHours," +
                "endpointP90Hours,movementP90Hours,absoluteTauDeltaP90MinutesPerDay," +
                "signedMeanTauDeltaMinutesPerDay,halfCycleErrorFraction,fullCycleErrorFraction",
        )
        results.filter { it.scores.isNotEmpty() }.forEach { result ->
            val summary = summarizeCausalForecasts(result.scores)
            writer.appendLine(
                listOf(
                    result.algorithmId,
                    csv(result.datasetId),
                    summary.windows,
                    result.missingWindows,
                    summary.circularPhaseMaeHours,
                    summary.unwrappedPhaseMaeHours,
                    summary.endpointP90Hours,
                    summary.movementP90Hours,
                    summary.absoluteTauDeltaP90MinutesPerDay,
                    summary.signedMeanTauDeltaMinutesPerDay,
                    summary.halfCycleErrorFraction,
                    summary.fullCycleErrorFraction,
                ).joinToString(","),
            )
        }
    }
}

private fun printResult(result: CausalDatasetResult) {
    if (result.scores.isEmpty()) {
        println("CAUSALRESULT\t${result.algorithmId}\t${result.datasetId}\tn=0\tmissing=${result.missingWindows}")
        return
    }
    printSummary(
        prefix = "CAUSALRESULT",
        id = "${result.algorithmId}\t${result.datasetId}",
        summary = summarizeCausalForecasts(result.scores),
        missing = result.missingWindows,
    )
}

private fun printSummary(prefix: String, id: String, summary: CausalForecastSummary, missing: Int) {
    println(
        "$prefix\t$id\tn=${summary.windows}\tmissing=$missing" +
            "\tcircular-mae=${format(summary.circularPhaseMaeHours)}h" +
            "\tunwrapped-mae=${format(summary.unwrappedPhaseMaeHours)}h" +
            "\tendpoint-p90=${format(summary.endpointP90Hours)}h" +
            "\tmovement-p90=${format(summary.movementP90Hours)}h" +
            "\ttau-p90=${format(summary.absoluteTauDeltaP90MinutesPerDay)}min/d" +
            "\ttau-bias=${format(summary.signedMeanTauDeltaMinutesPerDay)}min/d" +
            "\thalf-cycle=${format(summary.halfCycleErrorFraction * 100.0)}%" +
            "\tfull-cycle=${format(summary.fullCycleErrorFraction * 100.0)}%",
    )
}

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
private const val FORECAST_MARGIN_DAYS = 30
