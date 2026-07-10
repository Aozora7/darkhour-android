package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.kalman.analyzeCircadianKalman
import one.aozora.darkhour.core.model.SleepRecord

/**
 * Adapter for the app-switchable unwrapped Kalman estimator.
 */
object UnwrappedKalmanGroundTruthAlgorithm : GroundTruthAlgorithm {
    override val id = "unwrapped-kalman-prototype"

    override fun analyze(records: List<SleepRecord>): List<GroundTruthPredictionDay> =
        analyzeCircadianKalman(records).toGroundTruthPrediction()
}
