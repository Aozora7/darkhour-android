package one.aozora.darkhour.data

import java.time.Duration
import java.time.Instant

internal data class HealthConnectReadRange(
    val start: Instant,
    val end: Instant,
)

internal data class AccessibleHistoryRead(
    val range: HealthConnectReadRange,
    val ownedOnly: Boolean,
)

internal fun accessibleHistoryReads(
    range: HealthDataRange,
    now: Instant,
    recentStart: Instant,
): List<AccessibleHistoryRead> {
    val selectedStart = healthDataRangeStart(range, now)
    if (selectedStart >= recentStart) return emptyList()
    return buildList {
        add(AccessibleHistoryRead(HealthConnectReadRange(selectedStart, recentStart), ownedOnly = false))
        val ownedEnd = minOf(recentStart, now.minus(DEFAULT_HISTORY_DURATION))
        if (selectedStart < ownedEnd) {
            add(AccessibleHistoryRead(HealthConnectReadRange(selectedStart, ownedEnd), ownedOnly = true))
        }
    }
}

internal fun healthDataRangeStart(range: HealthDataRange, now: Instant): Instant = when (range) {
    HealthDataRange.EntireHistory -> Instant.EPOCH
    is HealthDataRange.Custom -> now.minus(Duration.ofDays(range.days.toLong()))
    HealthDataRange.DefaultPeriod -> now.minus(DEFAULT_HISTORY_DURATION)
}

internal fun healthConnectReadRanges(
    range: HealthDataRange,
    now: Instant,
    oldestAvailableStart: Instant? = null,
    initialImportDuration: Duration = DEFAULT_HISTORY_DURATION,
): List<HealthConnectReadRange> {
    val recentRange = healthConnectInitialReadRange(range, now, initialImportDuration)
    val recentStart = recentRange.start
    val oldestStart = oldestAvailableStart.takeIf { range == HealthDataRange.EntireHistory }
        ?: healthDataRangeStart(range, now)
    if (oldestStart >= recentStart) {
        return listOf(HealthConnectReadRange(oldestStart, now))
    }

    val ranges = mutableListOf(recentRange)
    var chunkEnd = recentStart
    while (chunkEnd > oldestStart) {
        val chunkStart = maxOf(oldestStart, chunkEnd.minus(OLDER_HISTORY_CHUNK_DURATION))
        ranges += HealthConnectReadRange(chunkStart, chunkEnd)
        chunkEnd = chunkStart
    }
    return ranges
}

internal fun healthConnectInitialReadRange(
    range: HealthDataRange,
    now: Instant,
    initialImportDuration: Duration,
): HealthConnectReadRange {
    val maximumDuration = when (range) {
        HealthDataRange.DefaultPeriod -> DEFAULT_HISTORY_DURATION
        HealthDataRange.EntireHistory -> initialImportDuration.coerceAtLeast(Duration.ofDays(1))
        is HealthDataRange.Custom -> Duration.ofDays(range.days.toLong())
    }
    val duration = initialImportDuration
        .coerceAtLeast(Duration.ofDays(1))
        .coerceAtMost(maximumDuration)
    return HealthConnectReadRange(now.minus(duration), now)
}

internal val DEFAULT_HISTORY_DURATION: Duration = Duration.ofDays(30)

private val OLDER_HISTORY_CHUNK_DURATION = Duration.ofDays(180)
