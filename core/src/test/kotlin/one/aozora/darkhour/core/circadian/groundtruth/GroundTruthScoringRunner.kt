package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry

/** Explicit real-data evaluation entry point; never executed by the unit-test task. */
object GroundTruthScoringRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        check(GroundTruthFixtures.isAvailable) {
            "Private ground-truth fixtures are not available on the scoring runtime classpath."
        }
        val requested = args.firstOrNull { it.startsWith("--algorithms=") }
            ?.substringAfter('=')
            ?: "all"
        val algorithms = if (requested == "all") {
            CircadianAlgorithmRegistry.algorithms
        } else {
            requested.split(',').map(CircadianAlgorithmRegistry::algorithm)
        }
        val datasets = GroundTruthFixtures.loadAll()
        check(datasets.all { it.records.isNotEmpty() && it.overlay.isNotEmpty() && it.controlPoints.isNotEmpty() }) {
            "Every private scoring dataset must contain records, an overlay, and control points."
        }

        for (algorithm in algorithms) {
            for (dataset in datasets) {
                val prediction = CircadianAlgorithmRegistry.analyze(
                    records = dataset.records,
                    algorithmId = algorithm.id,
                ).toGroundTruthPrediction()
                val score = scoreAgainstGroundTruth(prediction, dataset)
                check(score.pairedDays > 0 && score.meanAbsolutePhaseErrorHours.isFinite()) {
                    "${algorithm.id}/${dataset.id} produced an invalid score."
                }
                println(score.summary(dataset.id, algorithm.id))
            }
        }
    }
}
