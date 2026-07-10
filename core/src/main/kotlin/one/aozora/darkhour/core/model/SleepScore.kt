package one.aozora.darkhour.core.model

import kotlin.math.round

/**
 * Estimates how suitable a sleep session is as a circadian-night observation.
 *
 * This is an evidence-informed heuristic, not a clinical or diagnostic score. It uses the
 * objective dimensions available consistently from Health Connect:
 *
 * - Duration reaches full credit at 7 hours. The 4-7 hour ramp is a continuous version of the
 *   duration bands used by the Pittsburgh Sleep Quality Index and the AASM/SRS recommendation
 *   that adults regularly obtain at least 7 hours of sleep.
 * - Continuity interpolates across established quality bands. The PSQI's lowest efficiency band
 *   begins below 65%, while National Sleep Foundation consensus identifies at least 85% efficiency
 *   and at most 20 minutes awake after sleep onset as good. Fifty minutes is used as a smooth
 *   no-credit point within the adult range (41 minutes or more) that does not indicate good quality.
 *
 * Duration and continuity contribute equally. Efficiency and awake time contribute equally to
 * continuity because they capture complementary relative and absolute disruption. The available
 * awake minutes are used as a wake-after-sleep-onset proxy because the imported domain model does
 * not distinguish sleep latency and terminal wake. When wake data is unavailable, the score uses
 * duration alone rather than treating missing data as perfect or penalizing records from a less
 * capable data source.
 *
 * Sleep-stage composition is deliberately excluded: expert consensus is weaker for architecture
 * as a quality measure, and consumer wearables classify stages less reliably than sleep and wake.
 * Timing and regularity are excluded to avoid circularly weighting a circadian timing observation
 * by the timing pattern that the observation is intended to estimate.
 *
 * References:
 * - Buysse et al., Psychiatry Research 28 (1989), doi:10.1016/0165-1781(89)90047-4
 * - Watson et al., Journal of Clinical Sleep Medicine 11 (2015), doi:10.5664/jcsm.4758
 * - Ohayon et al., Sleep Health 3 (2017), doi:10.1016/j.sleh.2016.11.006
 * - Chinoy et al., Sleep 44 (2021), doi:10.1093/sleep/zsaa291
 */
fun calculateSleepScore(record: SleepRecord): Double {
    val asleepHours = record.minutesAsleep.coerceAtLeast(0) / 60.0
    val durationAdequacy = normalizeBetween(
        value = asleepHours,
        low = 4.0,
        high = 7.0,
    )

    val continuityAdequacy = calculateContinuityAdequacy(record)
    val score = if (continuityAdequacy == null) {
        durationAdequacy
    } else {
        (durationAdequacy + continuityAdequacy) / 2.0
    }

    return round(score.coerceIn(0.0, 1.0) * 100.0) / 100.0
}

private fun calculateContinuityAdequacy(record: SleepRecord): Double? {
    val timeInBedMinutes = record.durationMs.coerceAtLeast(0L) / 60_000.0
    if (timeInBedMinutes <= 0.0) return null

    val awakeMinutes = when {
        record.stages != null -> record.stages.wake.coerceAtLeast(0).toDouble()
        record.minutesAwake > 0 -> record.minutesAwake.toDouble()
        record.efficiency in 1..99 -> timeInBedMinutes * (1.0 - record.efficiency / 100.0)
        else -> return null
    }

    val efficiency = (record.minutesAsleep.coerceAtLeast(0) / timeInBedMinutes).coerceIn(0.0, 1.0)
    val efficiencyAdequacy = normalizeBetween(
        value = efficiency,
        low = 0.65,
        high = 0.85,
    )
    val wakeAdequacy = 1.0 - normalizeBetween(
        value = awakeMinutes,
        low = 20.0,
        high = 50.0,
    )

    return (efficiencyAdequacy + wakeAdequacy) / 2.0
}

private fun normalizeBetween(value: Double, low: Double, high: Double): Double =
    ((value - low) / (high - low)).coerceIn(0.0, 1.0)
