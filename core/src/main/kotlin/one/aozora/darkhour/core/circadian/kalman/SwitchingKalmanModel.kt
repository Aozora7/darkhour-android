package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.DurationSmoothingConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

data class SwitchingKalmanConfig(
    val driftPrior: Double = 0.51,
    val initialPhaseVariance: Double = 4.0,
    val initialDriftVariance: Double = 1.0,
    val processPhaseVariance: Double = 0.49,
    val processDriftVariance: Double = 0.0001,
    val measurementVarianceAtUnitWeight: Double = 10.0,
    val regimePriorDays: Double = 90.0,
    val regimeMinEvidence: Double = 7.0,
    val driftResetVariance: Double = 1.0,
    val offsetResetVariance: Double = 4.0,
    val changeCommitProbability: Double = 0.95,
    val durationSmoothing: DurationSmoothingConfig = DurationSmoothingConfig(),
) {
    init {
        require(processPhaseVariance > 0.0)
        require(processDriftVariance > 0.0)
        require(measurementVarianceAtUnitWeight > 0.0)
        require(regimePriorDays > 1.0)
        require(regimeMinEvidence > 0.0)
        require(driftResetVariance > 0.0)
        require(offsetResetVariance > 0.0)
        require(changeCommitProbability in 0.0..1.0)
    }
}

internal data class SwitchingState(
    val phase: Double,
    val drift: Double,
    val offset: Double,
    val covariance: Matrix3,
)

internal data class Matrix3(val values: DoubleArray) {
    init { require(values.size == 9) }
    operator fun get(row: Int, column: Int): Double = values[row * 3 + column]
    fun copyValues(): DoubleArray = values.copyOf()

    companion object {
        fun diagonal(a: Double, b: Double, c: Double) = Matrix3(
            doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c),
        )
    }
}

internal data class SwitchingFilterStep(
    val dayNumber: Int,
    val predicted: SwitchingState,
    val filtered: SwitchingState,
    val predictedNext: SwitchingState? = null,
)

internal data class SwitchingTrendState(
    val dayNumber: Int,
    val phase: Double,
    val drift: Double,
    val offset: Double,
    val phaseVariance: Double,
)

internal fun initialSwitchingState(
    phase: Double,
    config: SwitchingKalmanConfig,
    drift: Double = config.driftPrior,
    offset: Double = 0.0,
): SwitchingState = SwitchingState(
    phase,
    drift,
    offset,
    Matrix3.diagonal(config.initialPhaseVariance, config.initialDriftVariance, config.offsetResetVariance),
)

internal fun predictSwitchingState(
    state: SwitchingState,
    days: Int,
    config: SwitchingKalmanConfig,
): SwitchingState {
    var result = state
    repeat(days.coerceAtLeast(0)) {
        val p = result.covariance
        result = SwitchingState(
            phase = result.phase + result.drift,
            drift = result.drift,
            offset = result.offset,
            covariance = Matrix3(
                doubleArrayOf(
                    p[0, 0] + 2.0 * p[0, 1] + p[1, 1] + config.processPhaseVariance,
                    p[0, 1] + p[1, 1],
                    p[0, 2] + p[1, 2],
                    p[1, 0] + p[1, 1],
                    p[1, 1] + config.processDriftVariance,
                    p[1, 2],
                    p[2, 0] + p[2, 1],
                    p[2, 1],
                    p[2, 2],
                ),
            ),
        )
    }
    return result
}

internal data class SwitchingUpdate(
    val state: SwitchingState,
    val logLikelihood: Double,
    val resolvedObservation: Double,
)

internal fun updateSwitchingState(
    predicted: SwitchingState,
    observation: KalmanObservation,
    config: SwitchingKalmanConfig,
): SwitchingUpdate {
    val expected = predicted.phase + predicted.offset
    val resolved = observation.midpointHour + round((expected - observation.midpointHour) / 24.0) * 24.0
    val p = predicted.covariance
    val measurementVariance = config.measurementVarianceAtUnitWeight / max(observation.weight, 1e-6)
    val innovationVariance = p[0, 0] + p[0, 2] + p[2, 0] + p[2, 2] + measurementVariance
    val innovation = resolved - expected
    val ph = doubleArrayOf(
        p[0, 0] + p[0, 2],
        p[1, 0] + p[1, 2],
        p[2, 0] + p[2, 2],
    )
    val gain = DoubleArray(3) { ph[it] / innovationVariance }
    val values = p.copyValues()
    for (row in 0..2) {
        for (column in 0..2) {
            val hpColumn = p[0, column] + p[2, column]
            values[row * 3 + column] -= gain[row] * hpColumn
        }
    }
    symmetrizeAndFloor(values)
    val updated = SwitchingState(
        phase = predicted.phase + gain[0] * innovation,
        drift = predicted.drift + gain[1] * innovation,
        offset = predicted.offset + gain[2] * innovation,
        covariance = Matrix3(values),
    )
    return SwitchingUpdate(
        state = updated,
        logLikelihood = contaminatedGaussianLogLikelihood(innovation, innovationVariance),
        resolvedObservation = resolved,
    )
}

internal fun resetSwitchingState(
    predicted: SwitchingState,
    config: SwitchingKalmanConfig,
): SwitchingState = SwitchingState(
    phase = predicted.phase,
    drift = config.driftPrior,
    offset = 0.0,
    covariance = Matrix3.diagonal(
        max(predicted.covariance[0, 0], 1e-6),
        config.driftResetVariance,
        config.offsetResetVariance,
    ),
)

private fun contaminatedGaussianLogLikelihood(residual: Double, variance: Double): Double {
    val regular = gaussianDensity(residual, variance)
    val outlier = gaussianDensity(residual, variance + OUTLIER_VARIANCE_HOURS_SQUARED)
    return ln((1.0 - OUTLIER_PROBABILITY) * regular + OUTLIER_PROBABILITY * outlier + 1e-300)
}

private fun gaussianDensity(value: Double, variance: Double): Double =
    exp(-0.5 * value * value / variance) / sqrt(2.0 * PI * variance)

internal fun fitSwitchingKalmanTrend(
    observations: List<KalmanObservation>,
    firstDay: Int,
    lastDay: Int,
    config: SwitchingKalmanConfig,
    initialState: SwitchingState? = null,
): List<SwitchingTrendState> {
    if (observations.isEmpty() || lastDay < firstDay) return emptyList()
    val byDay = observations.associateBy(KalmanObservation::dayNumber)
    val firstObservation = observations.minBy(KalmanObservation::dayNumber)
    var state = initialState ?: initialSwitchingState(
        phase = firstObservation.midpointHour - (firstObservation.dayNumber - firstDay) * config.driftPrior,
        config = config,
    )
    val steps = ArrayList<SwitchingFilterStep>(lastDay - firstDay + 1)
    for (day in firstDay..lastDay) {
        if (day != firstDay) state = predictSwitchingState(state, 1, config)
        val predicted = state
        byDay[day]?.let { state = updateSwitchingState(predicted, it, config).state }
        steps += SwitchingFilterStep(day, predicted, state)
    }
    for (index in 0 until steps.lastIndex) {
        steps[index] = steps[index].copy(predictedNext = predictSwitchingState(steps[index].filtered, 1, config))
    }
    return smoothSwitchingSteps(steps)
}

private fun smoothSwitchingSteps(steps: List<SwitchingFilterStep>): List<SwitchingTrendState> {
    val smoothed = steps.map(SwitchingFilterStep::filtered).toMutableList()
    for (index in smoothed.lastIndex - 1 downTo 0) {
        val filtered = steps[index].filtered
        val predicted = checkNotNull(steps[index].predictedNext)
        val gain = multiply(multiply(filtered.covariance, transpose(TRANSITION)), inverse(predicted.covariance))
        val innovation = doubleArrayOf(
            smoothed[index + 1].phase - predicted.phase,
            smoothed[index + 1].drift - predicted.drift,
            smoothed[index + 1].offset - predicted.offset,
        )
        val correction = multiply(gain, innovation)
        smoothed[index] = filtered.copy(
            phase = filtered.phase + correction[0],
            drift = filtered.drift + correction[1],
            offset = filtered.offset + correction[2],
        )
    }
    return smoothed.mapIndexed { index, state ->
        SwitchingTrendState(
            dayNumber = steps[index].dayNumber,
            phase = state.phase,
            drift = state.drift,
            offset = state.offset,
            phaseVariance = state.covariance[0, 0] + state.covariance[2, 2] +
                2.0 * state.covariance[0, 2],
        )
    }
}

private fun symmetrizeAndFloor(values: DoubleArray) {
    for (row in 0..2) {
        values[row * 3 + row] = max(values[row * 3 + row], 1e-9)
        for (column in row + 1..2) {
            val mean = (values[row * 3 + column] + values[column * 3 + row]) / 2.0
            values[row * 3 + column] = mean
            values[column * 3 + row] = mean
        }
    }
}

private fun transpose(matrix: Matrix3): Matrix3 = Matrix3(
    DoubleArray(9) { index -> matrix[index % 3, index / 3] },
)

private fun multiply(left: Matrix3, right: Matrix3): Matrix3 = Matrix3(
    DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (0..2).sumOf { left[row, it] * right[it, column] }
    },
)

private fun multiply(matrix: Matrix3, vector: DoubleArray): DoubleArray =
    DoubleArray(3) { row -> (0..2).sumOf { matrix[row, it] * vector[it] } }

private fun inverse(matrix: Matrix3): Matrix3 {
    val a = matrix[0, 0]; val b = matrix[0, 1]; val c = matrix[0, 2]
    val d = matrix[1, 0]; val e = matrix[1, 1]; val f = matrix[1, 2]
    val g = matrix[2, 0]; val h = matrix[2, 1]; val i = matrix[2, 2]
    val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if (abs(determinant) < 1e-12) return Matrix3.diagonal(0.0, 0.0, 0.0)
    return Matrix3(
        doubleArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d,
        ).map { it / determinant }.toDoubleArray(),
    )
}

private val TRANSITION = Matrix3(
    doubleArrayOf(1.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
)
private const val OUTLIER_PROBABILITY = 0.03
private const val OUTLIER_VARIANCE_HOURS_SQUARED = 36.0
internal const val SWITCHING_BEAM_WIDTH = 32
