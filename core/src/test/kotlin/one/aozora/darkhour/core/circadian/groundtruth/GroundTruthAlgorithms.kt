package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord

/**
 * Adapter for the app-switchable unwrapped Kalman estimator.
 */
object UnwrappedKalmanGroundTruthAlgorithm : GroundTruthAlgorithm {
    override val id = CircadianAlgorithmRegistry.KALMAN_ID

    override fun analyze(records: List<SleepRecord>): List<GroundTruthPredictionDay> =
        CircadianAlgorithmRegistry.analyze(records, algorithmId = id).toGroundTruthPrediction()
}
