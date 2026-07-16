package one.aozora.darkhour.data

import androidx.health.connect.client.records.metadata.Metadata
import one.aozora.darkhour.core.model.SleepRecord
import one.aozora.darkhour.core.model.SleepStageInterval
import one.aozora.darkhour.core.model.SleepStageLevel
import one.aozora.darkhour.core.model.SleepStages
import one.aozora.darkhour.core.model.calculateSleepScore
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal data class ResolvedImportedSleepRecords(
    val records: List<ImportedSleepRecord>,
    val analysisRecords: List<ImportedSleepRecord>,
)

internal fun resolveImportedSleepRecords(
    importedRecords: List<ImportedSleepRecord>,
    ownedPackageName: String? = null,
): ResolvedImportedSleepRecords {
    if (importedRecords.isEmpty()) {
        return ResolvedImportedSleepRecords(emptyList(), emptyList())
    }

    val records = importedRecords
        .groupBy(ImportedSleepRecord::deduplicationIdentity)
        .values
        .map { records -> canonicalRecord(records, ownedPackageName) }
        .sortedWith(importedRecordTimeOrder)
    if (records.size == 1) {
        return ResolvedImportedSleepRecords(records, records)
    }

    val analysisSets = DisjointSets(records.size)
    val duplicateSets = DisjointSets(records.size)
    val sortedIndices = records.indices.sortedWith(
        compareBy<Int> { records[it].record.startTime }
            .thenBy { records[it].record.endTime }
            .thenBy { records[it].stableIdentity() },
    )
    val active = mutableListOf<Int>()
    for (currentIndex in sortedIndices) {
        val current = records[currentIndex]
        active.removeAll { activeIndex ->
            records[activeIndex].record.endTime <= current.record.startTime
        }
        for (activeIndex in active) {
            val other = records[activeIndex]
            val overlapMillis = overlapMillis(current.record, other.record)
            if (overlapMillis <= 0L) continue

            analysisSets.union(currentIndex, activeIndex)
            val shorterMillis = minOf(current.record.durationMs, other.record.durationMs)
            if (
                shorterMillis > 0L &&
                overlapMillis.toDouble() / shorterMillis >= MOSTLY_OVERLAPPING_RATIO
            ) {
                duplicateSets.union(currentIndex, activeIndex)
            }
        }
        active += currentIndex
    }

    val analysisComponents = records.indices
        .groupBy(analysisSets::find)
        .values
        .map { indices -> indices.map(records::get) }
    val analysisRecordByMemberIdentity = mutableMapOf<String, ImportedSleepRecord>()
    val analysisRecords = analysisComponents.map { members ->
        val consolidated = if (members.size == 1) {
            members.single()
        } else {
            consolidateRecords(members, ownedPackageName)
        }
        members.forEach { member ->
            analysisRecordByMemberIdentity[member.stableIdentity()] = consolidated
        }
        consolidated
    }.sortedWith(importedRecordTimeOrder)

    val displayRecords = records.indices
        .groupBy(duplicateSets::find)
        .values
        .map { indices -> canonicalRecord(indices.map(records::get), ownedPackageName) }
        .map { displayRecord ->
            val episode = checkNotNull(analysisRecordByMemberIdentity[displayRecord.stableIdentity()])
            if (displayRecord.record.isMainSleep == episode.record.isMainSleep) {
                displayRecord
            } else {
                displayRecord.copy(
                    record = displayRecord.record.copy(isMainSleep = episode.record.isMainSleep),
                )
            }
        }
        .sortedWith(importedRecordTimeOrder)

    return ResolvedImportedSleepRecords(displayRecords, analysisRecords)
}

internal fun preferredImportedSleepRecord(
    first: ImportedSleepRecord,
    second: ImportedSleepRecord,
    ownedPackageName: String? = null,
): ImportedSleepRecord = canonicalRecord(listOf(first, second), ownedPackageName)

internal fun ImportedSleepRecord.deduplicationIdentity(): String =
    sourceRecordId?.let { "health-connect:$it" } ?: "fallback:${record.logId}"

private fun consolidateRecords(
    members: List<ImportedSleepRecord>,
    ownedPackageName: String?,
): ImportedSleepRecord {
    val rankedMembers = members.sortedWith(canonicalRecordOrder(ownedPackageName))
    val canonical = rankedMembers.first()
    val startOwner = rankedMembers
        .filter { it.record.startTime == members.minOf { member -> member.record.startTime } }
        .first()
    val endOwner = rankedMembers
        .filter { it.record.endTime == members.maxOf { member -> member.record.endTime } }
        .first()
    val startTime = startOwner.record.startTime
    val endTime = endOwner.record.endTime
    val duration = Duration.between(startTime, endTime)
    val startOffset = startOwner.record.startZoneOffset
        ?: canonical.record.startZoneOffset
        ?: ZoneOffset.UTC
    val endOffset = endOwner.record.endZoneOffset
        ?: canonical.record.endZoneOffset
        ?: startOffset
    val resolvedStages = resolveStages(rankedMembers, startTime, endTime)
    val stageSeconds = resolvedStages
        .groupingBy(SleepStageInterval::level)
        .fold(0L) { total, stage -> total + stage.seconds }
    val wakeMinutes = ((stageSeconds[SleepStageLevel.WAKE] ?: 0L) / 60L).toInt()
    val totalMinutes = duration.toMinutes().toInt().coerceAtLeast(0)
    val asleepMinutes = (totalMinutes - wakeMinutes).coerceAtLeast(0)
    val durationMillis = duration.toMillis()
    val rawRecord = SleepRecord(
        logId = stableLogId(
            sourceId = members
                .map(ImportedSleepRecord::stableIdentity)
                .sorted()
                .joinToString(prefix = "overlap:", separator = "|"),
            startMillis = startTime.toEpochMilli(),
            endMillis = endTime.toEpochMilli(),
        ),
        dateOfSleep = startTime.atOffset(startOffset).toLocalDate(),
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMillis,
        durationHours = durationMillis / MILLIS_PER_HOUR,
        efficiency = if (totalMinutes > 0) asleepMinutes * 100 / totalMinutes else 0,
        minutesAsleep = asleepMinutes,
        minutesAwake = wakeMinutes,
        isMainSleep = duration >= MAIN_SLEEP_MINIMUM_DURATION,
        stages = if (resolvedStages.isEmpty()) {
            null
        } else {
            SleepStages(
                deep = ((stageSeconds[SleepStageLevel.DEEP] ?: 0L) / 60L).toInt(),
                light = ((stageSeconds[SleepStageLevel.LIGHT] ?: 0L) / 60L).toInt(),
                rem = ((stageSeconds[SleepStageLevel.REM] ?: 0L) / 60L).toInt(),
                wake = wakeMinutes,
            )
        },
        stageData = resolvedStages,
        startZoneOffset = startOffset,
        endZoneOffset = endOffset,
    )
    val record = rawRecord.copy(sleepScore = calculateSleepScore(rawRecord))
    return ImportedSleepRecord(
        record = record,
        sourceRecordId = null,
        sourcePackageName = null,
        sourceDevice = null,
        sourceRecordingMethod = canonical.sourceRecordingMethod,
        sourceLastModifiedTime = members.maxOf(ImportedSleepRecord::sourceLastModifiedTime),
        specificStageSeconds = resolvedStages.sumOf { it.seconds.toLong() },
        usableStageSeconds = resolvedStages.sumOf { it.seconds.toLong() },
    )
}

private fun resolveStages(
    rankedMembers: List<ImportedSleepRecord>,
    unionStart: Instant,
    unionEnd: Instant,
): List<SleepStageInterval> {
    data class RankedStage(
        val rank: Int,
        val interval: SleepStageInterval,
        val endTime: Instant,
    )

    val stages = rankedMembers.flatMapIndexed { rank, member ->
        member.record.stageData.mapNotNull { stage ->
            val stageEnd = stage.startTime.plusSeconds(stage.seconds.toLong())
            val clippedStart = maxOf(stage.startTime, unionStart)
            val clippedEnd = minOf(stageEnd, unionEnd)
            if (clippedStart >= clippedEnd) {
                null
            } else {
                RankedStage(
                    rank = rank,
                    interval = stage.copy(startTime = clippedStart),
                    endTime = clippedEnd,
                )
            }
        }
    }
    if (stages.isEmpty()) return emptyList()

    val boundaries = buildSet {
        add(unionStart)
        add(unionEnd)
        stages.forEach { stage ->
            add(stage.interval.startTime)
            add(stage.endTime)
        }
    }.sorted()
    val resolved = mutableListOf<SleepStageInterval>()
    boundaries.zipWithNext().forEach { (segmentStart, segmentEnd) ->
        val winner = stages
            .asSequence()
            .filter { stage ->
                stage.interval.startTime <= segmentStart && stage.endTime >= segmentEnd
            }
            .minWithOrNull(
                compareBy<RankedStage> { it.rank }
                    .thenBy { it.interval.level.ordinal },
            )
            ?: return@forEach
        val seconds = Duration.between(segmentStart, segmentEnd).seconds.toInt()
        if (seconds <= 0) return@forEach
        val previous = resolved.lastOrNull()
        val previousEnd = previous?.startTime?.plusSeconds(previous.seconds.toLong())
        if (previous != null && previous.level == winner.interval.level && previousEnd == segmentStart) {
            resolved[resolved.lastIndex] = previous.copy(seconds = previous.seconds + seconds)
        } else {
            resolved += SleepStageInterval(
                startTime = segmentStart,
                level = winner.interval.level,
                seconds = seconds,
            )
        }
    }
    return resolved
}

private fun canonicalRecord(
    records: List<ImportedSleepRecord>,
    ownedPackageName: String?,
): ImportedSleepRecord = records.sortedWith(canonicalRecordOrder(ownedPackageName)).first()

private fun canonicalRecordOrder(ownedPackageName: String?) =
    compareByDescending<ImportedSleepRecord> { it.specificStageCoverageSeconds() }
        .thenByDescending { it.usableStageCoverageSeconds() }
        .thenByDescending { it.record.durationMs }
        .thenByDescending { it.recordingMethodRank() }
        .thenBy { it.isOwnedAppVariant(ownedPackageName) }
        .thenByDescending { it.sourceLastModifiedTime }
        .thenBy { it.sourcePackageName.orEmpty() }
        .thenBy { it.sourceRecordId.orEmpty() }
        .thenBy { it.record.logId }

private val importedRecordTimeOrder =
    compareBy<ImportedSleepRecord> { it.record.startTime }
        .thenBy { it.record.endTime }
        .thenBy { it.stableIdentity() }

private fun ImportedSleepRecord.specificStageCoverageSeconds(): Long =
    if (specificStageSeconds >= 0) {
        specificStageSeconds
    } else {
        record.stageData.sumOf { it.seconds.toLong().coerceAtLeast(0) }
    }

private fun ImportedSleepRecord.usableStageCoverageSeconds(): Long =
    if (usableStageSeconds >= 0) {
        usableStageSeconds
    } else {
        record.stageData.sumOf { it.seconds.toLong().coerceAtLeast(0) }
    }

private fun ImportedSleepRecord.recordingMethodRank(): Int = when (sourceRecordingMethod) {
    Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> 3
    Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> 2
    Metadata.RECORDING_METHOD_UNKNOWN -> 1
    Metadata.RECORDING_METHOD_MANUAL_ENTRY -> 0
    else -> 1
}

private fun ImportedSleepRecord.isOwnedAppVariant(ownedPackageName: String?): Boolean {
    if (ownedPackageName == null || sourcePackageName == null) return false
    val basePackageName = ownedPackageName
        .removeSuffix(DEBUG_APPLICATION_ID_SUFFIX)
        .removeSuffix(DEMO_APPLICATION_ID_SUFFIX)
    return sourcePackageName == basePackageName ||
        sourcePackageName == basePackageName + DEBUG_APPLICATION_ID_SUFFIX ||
        sourcePackageName == basePackageName + DEMO_APPLICATION_ID_SUFFIX
}

private fun ImportedSleepRecord.stableIdentity(): String =
    "${sourcePackageName.orEmpty()}:${deduplicationIdentity()}"

private fun overlapMillis(first: SleepRecord, second: SleepRecord): Long =
    Duration.between(
        maxOf(first.startTime, second.startTime),
        minOf(first.endTime, second.endTime),
    ).toMillis().coerceAtLeast(0)

private class DisjointSets(size: Int) {
    private val parents = IntArray(size) { it }
    private val ranks = IntArray(size)

    fun find(index: Int): Int {
        if (parents[index] != index) {
            parents[index] = find(parents[index])
        }
        return parents[index]
    }

    fun union(first: Int, second: Int) {
        val firstRoot = find(first)
        val secondRoot = find(second)
        if (firstRoot == secondRoot) return
        when {
            ranks[firstRoot] < ranks[secondRoot] -> parents[firstRoot] = secondRoot
            ranks[firstRoot] > ranks[secondRoot] -> parents[secondRoot] = firstRoot
            else -> {
                parents[secondRoot] = firstRoot
                ranks[firstRoot] += 1
            }
        }
    }
}

private const val MOSTLY_OVERLAPPING_RATIO = 0.8
private const val MILLIS_PER_HOUR = 3_600_000.0
private const val DEBUG_APPLICATION_ID_SUFFIX = ".debug"
private const val DEMO_APPLICATION_ID_SUFFIX = ".demo"
private val MAIN_SLEEP_MINIMUM_DURATION = Duration.ofHours(4)
