package one.aozora.darkhour.core.circadian.adaptive

/**
 * Fits ordinary low-noise Kalman dynamics with causally detected, fixed-lag
 * transition resets. Detection is separate from filtering so confirmation
 * latency and boundary placement remain observable and independently tested.
 */
fun fitAdaptiveKalman(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int = observations.minOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: 0,
    lastDay: Int = observations.maxOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: firstDay,
    config: AdaptiveKalmanConfig = AdaptiveKalmanConfig(),
    transitionConfig: AdaptiveKalmanTransitionConfig? = AdaptiveKalmanTransitionConfig(),
): AdaptiveKalmanFit {
    if (observations.isEmpty() || lastDay < firstDay) return AdaptiveKalmanFit(emptyList(), emptyList())
    val sorted = observations.sortedBy(AdaptiveKalmanObservation::dayNumber)
    val transitions = transitionConfig?.let {
        detectAdaptiveKalmanTransitions(sorted, firstDay, lastDay, config, it)
    }.orEmpty()
    return AdaptiveKalmanFit(
        states = runAdaptiveSmoother(
            sorted,
            firstDay,
            lastDay,
            config,
            transitionConfig,
            transitions,
        ),
        transitions = transitions,
    )
}

/** Compatibility entry point for callers that only need the daily state series. */
fun fitAdaptiveKalmanTrend(
    observations: List<AdaptiveKalmanObservation>,
    firstDay: Int = observations.minOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: 0,
    lastDay: Int = observations.maxOfOrNull(AdaptiveKalmanObservation::dayNumber) ?: firstDay,
    config: AdaptiveKalmanConfig = AdaptiveKalmanConfig(),
    transitionConfig: AdaptiveKalmanTransitionConfig? = AdaptiveKalmanTransitionConfig(),
): List<AdaptiveKalmanState> =
    fitAdaptiveKalman(observations, firstDay, lastDay, config, transitionConfig).states
