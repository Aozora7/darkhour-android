package one.aozora.darkhour.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

fun calculateSleepScore(record: SleepRecord): Double {
    // Regression weights derived from fitting Fitbit sleep records with known quality scores.
    val intercept = 66.60654843292923
    val durationWeight = 9.071460795887447
    val deepRemWeight = 0.1110083920616658
    val wakePctWeight = -102.526819077646

    val asleepHours = record.minutesAsleep / 60.0
    val durationScore = when {
        asleepHours < 4.0 -> (asleepHours / 4.0) * 0.5
        asleepHours < 7.0 -> 0.5 + ((asleepHours - 4.0) / 3.0) * 0.5
        asleepHours <= 9.0 -> 1.0
        asleepHours < 12.0 -> 1.0 - (asleepHours - 9.0) / 3.0
        else -> 0.0
    }

    val deepPlusRemMinutes = record.stages?.let { stages ->
        (stages.deep + stages.rem).toDouble()
    } ?: (record.minutesAsleep * 0.39)

    val timeInBed = record.durationMs / 60_000.0
    val wakePct = record.stages?.let { stages ->
        if (timeInBed > 0.0) stages.wake / timeInBed else 0.0
    } ?: if (timeInBed > 0.0) {
        record.minutesAwake / timeInBed
    } else {
        0.15
    }

    val raw = intercept +
        durationWeight * durationScore +
        deepRemWeight * deepPlusRemMinutes +
        wakePctWeight * wakePct

    return round(max(0.0, min(100.0, raw))) / 100.0
}
