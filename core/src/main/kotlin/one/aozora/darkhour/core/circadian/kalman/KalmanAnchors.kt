package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.WeightedMidpointAnchor
import one.aozora.darkhour.core.circadian.prepareWeightedMidpointAnchors
import one.aozora.darkhour.core.model.SleepRecord
import java.time.LocalDate

/** Kalman's independently owned daily sleep-midpoint observations. */
internal typealias KalmanAnchor = WeightedMidpointAnchor

internal fun prepareKalmanAnchors(
    records: List<SleepRecord>,
    globalFirstDate: LocalDate,
    globalFirstDateMs: Long,
): List<KalmanAnchor> = prepareWeightedMidpointAnchors(records, globalFirstDate, globalFirstDateMs)
