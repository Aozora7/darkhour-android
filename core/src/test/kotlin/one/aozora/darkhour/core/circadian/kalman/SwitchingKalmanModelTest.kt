package one.aozora.darkhour.core.circadian.kalman

import one.aozora.darkhour.core.circadian.csf.SyntheticOptions
import one.aozora.darkhour.core.circadian.csf.TauSegment
import one.aozora.darkhour.core.circadian.csf.FragmentedPeriod
import one.aozora.darkhour.core.circadian.csf.generateSyntheticRecords
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class SwitchingKalmanModelTest {
    @Test
    fun branchWeightRegistryMaximaAreValidTogether() {
        val config = SwitchingKalmanConfig(
            genericChangeWeight = 0.50,
            offsetChangeWeight = 0.50,
        )
        val records = generateSyntheticRecords(options(days = 40, tau = 25.0, seed = 71))

        val analysis = analyzeCircadianSwitchingKalman(records, config = config)

        assertTrue(analysis.days.isNotEmpty())
    }

    @Test
    fun weightedUpdateUsesPhasePlusOffsetObservation() {
        val config = testConfig()
        val predicted = initialSwitchingState(phase = 10.0, drift = 1.0, offset = 2.0, config = config)
        val updated = updateSwitchingState(predicted, KalmanObservation(0, 13.0, 1.0), config)

        assertTrue(updated.state.phase > predicted.phase)
        assertTrue(updated.state.offset > predicted.offset)
        assertEquals(1.0, updated.resolvedObservation - 12.0, 1e-9)
        assertTrue(updated.logLikelihood.isFinite())
    }

    @Test
    fun predictionAdvancesPhaseButKeepsDriftAndOffset() {
        val config = testConfig()
        val state = initialSwitchingState(phase = 2.0, drift = 0.75, offset = -1.5, config = config)
        val predicted = predictSwitchingState(state, 4, config)

        assertEquals(0.75, predicted.drift, 1e-9)
        assertTrue(abs(predicted.offset) < 1.5)
        assertEquals(3.5, predicted.phase + predicted.offset, 1e-9)
    }

    @Test
    fun beamInferenceIsDeterministicAndBounded() {
        val records = syntheticStep(25.0, 24.0, transitionDay = 70, totalDays = 95)
        val observations = observations(records)

        val first = detectSwitchingKalmanChangePoints(observations, testConfig())
        val second = detectSwitchingKalmanChangePoints(observations, testConfig())

        assertEquals(
            first.map { listOf(it.dayNumber, it.confirmationDayNumber, it.posteriorProbability, it.newDrift) },
            second.map { listOf(it.dayNumber, it.confirmationDayNumber, it.posteriorProbability, it.newDrift) },
        )
        assertTrue(SWITCHING_BEAM_WIDTH <= 32)
    }

    @Test
    fun stableSeriesDoesNotCommitAChange() {
        listOf(24.0, 25.0).forEach { tau ->
            val records = generateSyntheticRecords(options(days = 120, tau = tau, seed = tau.toInt()))
            val analysis = analyzeCircadianSwitchingKalman(records, config = testConfig())
            assertTrue("stable tau $tau created ${analysis.changePoints}", analysis.changePoints.isEmpty())
        }
    }

    @Test
    fun registryDefaultsDoNotSplitStableSeries() {
        val records = generateSyntheticRecords(options(days = 180, tau = 25.0, seed = 41))
        val analysis = analyzeCircadianSwitchingKalman(records, config = SwitchingKalmanConfig())
        assertTrue("defaults split stable series: ${analysis.changePoints}", analysis.changePoints.isEmpty())
    }

    @Test
    fun persistentStepsAreCommittedInBothDirections() {
        listOf(25.0 to 24.0, 24.0 to 25.0).forEach { (before, after) ->
            val transition = 70
            val analysis = analyzeCircadianSwitchingKalman(
                syntheticStep(before, after, transition, 100),
                config = testConfig(),
            )
            val expected = START_DATE.plusDays(transition.toLong())
            val change = analysis.changePoints.minByOrNull { dayDistance(it.date, expected) }
            assertTrue("$before->$after produced no boundary", change != null)
            assertTrue("$before->$after boundary was ${change?.date}", dayDistance(checkNotNull(change).date, expected) <= 2)
            assertTrue("$before->$after drift was ${change.newDrift}", abs(change.newDrift - (after - 24.0)) < 0.35)
        }
    }

    @Test
    fun multiplePersistentRegimesCanBeCommitted() {
        val records = generateSyntheticRecords(
            options(
                days = 150,
                tau = 25.0,
                seed = 23,
                tauSegments = listOf(
                    TauSegment(50, 25.0),
                    TauSegment(100, 24.0),
                    TauSegment(150, 25.0),
                ),
            ),
        )
        val analysis = analyzeCircadianSwitchingKalman(
            records,
            config = testConfig().copy(driftPrior = 0.20),
        )

        assertTrue("expected two regimes: ${analysis.changePoints}", analysis.changePoints.size >= 2)
        assertTrue(dayDistance(analysis.changePoints[0].date, START_DATE.plusDays(50)) <= 2)
        assertTrue(dayDistance(analysis.changePoints[1].date, START_DATE.plusDays(100)) <= 2)
        assertTrue(
            "release ignored learned free-running prior: ${analysis.changePoints[1]}",
            analysis.changePoints[1].newDrift > 0.70,
        )
    }

    @Test
    fun spectralPriorRecoversStableFreeRunningTau() {
        val records = generateSyntheticRecords(options(days = 150, tau = 25.0, seed = 61))
        val drift = estimateSpectralFreeRunningDrift(observations(records))
        assertTrue("spectral drift was $drift", drift != null && abs(drift - 1.0) < 0.15)
    }

    @Test
    fun registryDefaultsDetectPersistentEntrainment() {
        val transition = 70
        val analysis = analyzeCircadianSwitchingKalman(
            syntheticStep(25.0, 24.0, transition, 105),
            config = SwitchingKalmanConfig(),
        )
        val expected = START_DATE.plusDays(transition.toLong())
        val change = analysis.changePoints.minByOrNull { dayDistance(it.date, expected) }

        assertTrue("defaults did not commit: ${analysis.changePoints}", change != null)
        assertTrue("default boundary was ${change?.date}", dayDistance(checkNotNull(change).date, expected) <= 2)
    }

    @Test
    fun persistentTailStepIsNotDilutedByLongHistory() {
        val transition = 700
        val analysis = analyzeCircadianSwitchingKalman(
            syntheticStep(25.0, 24.0, transition, transition + 20),
            config = SwitchingKalmanConfig(),
        )
        val expected = START_DATE.plusDays(transition.toLong())
        val change = analysis.changePoints.minByOrNull { dayDistance(it.date, expected) }

        assertTrue("long-history tail step produced no boundary: ${analysis.changePoints}", change != null)
        assertTrue("long-history boundary was ${change?.date}", dayDistance(checkNotNull(change).date, expected) <= 2)
        assertTrue("long-history confirmation was ${change.confirmationDate}", change.confirmationDate <= expected.plusDays(19))
    }

    @Test
    @Ignore
    fun registryDefaultsCommitPreferredStepWithinTenNights() {
        val transition = 90
        val expected = START_DATE.plusDays(transition.toLong())
        val analysis = registryAnalysis(syntheticStep(25.0, 24.0, transition, transition + 10))
        val change = analysis.changePoints.minByOrNull { dayDistance(it.date, expected) }

        assertTrue("preferred tail step produced no boundary: ${analysis.changePoints}", change != null)
        assertTrue("preferred boundary was ${change?.date}", dayDistance(checkNotNull(change).date, expected) <= 2)
        assertTrue("preferred confirmation was ${change.confirmationDate}", change.confirmationDate <= expected.plusDays(9))
    }

    @Test
    fun registryDefaultsDoNotCommitSmallGenericStepWithinTenNights() {
        val transition = 90
        val analysis = registryAnalysis(syntheticStep(24.8, 25.3, transition, transition + 10))

        assertTrue("small generic step committed too quickly: ${analysis.changePoints}", analysis.changePoints.isEmpty())
    }

    @Test
    fun timingRelocationDoesNotBecomeTerminalDrift() {
        val transition = 70
        val records = syntheticStep(25.0, 24.0, transition, 90).mapIndexed { day, record ->
            if (day < transition) record else shift(record, 5.0)
        }
        val analysis = analyzeCircadianSwitchingKalman(records, config = testConfig())
        val terminalDrift = analysis.days.filterNot { it.isGap || it.isForecast }.takeLast(5)
            .map { it.localDrift }.average()

        assertTrue("timing relocation was not committed: ${analysis.changePoints}", analysis.changePoints.isNotEmpty())
        assertTrue("terminal tau was ${24.0 + terminalDrift}", abs(terminalDrift) < 0.25)
    }

    @Test
    fun phaseRelocationDoesNotChangeTau() {
        val transition = 70
        val records = generateSyntheticRecords(options(days = 100, tau = 25.0, seed = 29))
            .mapIndexed { day, record -> if (day < transition) record else shift(record, 5.0) }
        val analysis = analyzeCircadianSwitchingKalman(records, config = testConfig())
        val terminalDrift = analysis.days.filterNot { it.isGap || it.isForecast }.takeLast(5)
            .map { it.localDrift }.average()

        assertTrue("phase relocation changed tau to ${24.0 + terminalDrift}", abs(terminalDrift - 1.0) < 0.25)
    }

    @Test
    fun sleepPlacementOffsetDoesNotCreateCircadianPhaseDiscontinuity() {
        val transition = 70
        val records = generateSyntheticRecords(options(days = 100, tau = 25.0, seed = 53))
            .mapIndexed { day, record -> if (day < transition) record else shift(record, 5.0) }
        val analysis = analyzeCircadianSwitchingKalman(records, config = testConfig())
        val boundary = analysis.changePoints.first().date
        val days = analysis.days.filterNot { it.isGap || it.isForecast }.associateBy { it.date }
        val previous = days.getValue(boundary.minusDays(1))
        val current = days.getValue(boundary)
        val movement = circularDifference(midpoint(current), midpoint(previous))

        assertTrue("offset produced a $movement h phase jump at $boundary", abs(movement - 1.0) < 1.0)
    }

    @Test
    fun sparseAndLongGapStableSeriesDoNotCreateChanges() {
        val stable = generateSyntheticRecords(options(days = 150, tau = 25.0, seed = 37))
        val sparse = stable.filterIndexed { index, _ -> index % 3 != 1 }
        val gappy = stable.filterIndexed { index, _ -> index !in 55..80 }

        listOf("sparse" to sparse, "gappy" to gappy).forEach { (name, records) ->
            val analysis = analyzeCircadianSwitchingKalman(records, config = SwitchingKalmanConfig())
            assertTrue("$name created ${analysis.changePoints}", analysis.changePoints.isEmpty())
        }
        assertTrue(analyzeCircadianSwitchingKalman(gappy).days.any { it.isGap })
    }

    @Test
    fun gradualDriftAndNoisySeriesDoNotCreateChanges() {
        val gradual = generateSyntheticRecords(options(days = 140, tau = 24.4, seed = 31))
            .mapIndexed { day, record -> shift(record, 0.5 * (0.4 / 139.0) * day * day) }
        val noisy = generateSyntheticRecords(
            SyntheticOptions(
                days = 140,
                tau = 25.0,
                noise = 0.75,
                seed = 19,
                quality = 1.0,
                startDate = START_DATE,
            ),
        )
        listOf("gradual" to gradual, "noisy" to noisy).forEach { (name, records) ->
            val changes = analyzeCircadianSwitchingKalman(records, config = SwitchingKalmanConfig()).changePoints
            assertTrue("$name created $changes", changes.isEmpty())
        }
    }

    @Test
    fun napsOutliersAndFragmentationDoNotCreateChanges() {
        val records = generateSyntheticRecords(
            SyntheticOptions(
                days = 140,
                tau = 25.0,
                noise = 0.2,
                seed = 47,
                quality = 1.0,
                napFraction = 0.25,
                outlierFraction = 0.08,
                fragmentedPeriods = listOf(FragmentedPeriod(45, 58, 3, 2.0)),
                startDate = START_DATE,
            ),
        )
        val changes = analyzeCircadianSwitchingKalman(records, config = SwitchingKalmanConfig()).changePoints
        assertTrue("contamination created $changes", changes.isEmpty())
    }

    @Test
    fun committedBoundaryIsStableAcrossLaterPrefixes() {
        val records = syntheticStep(25.0, 24.0, 70, 105)
        val first = analyzeCircadianSwitchingKalman(records.take(90), config = testConfig()).changePoints
        val later = analyzeCircadianSwitchingKalman(records, config = testConfig()).changePoints

        assertTrue("prefix did not commit", first.isNotEmpty())
        assertEquals(first.first().date, later.first().date)
        assertEquals(first.first().confirmationDate, later.first().confirmationDate)
    }

    @Test
    fun committedRegimeProtectsEarlierHistoryAndControlsForecast() {
        val records = syntheticStep(25.0, 24.0, 70, 105)
        val prefix = analyzeCircadianSwitchingKalman(records.take(70), config = testConfig())
        val full = analyzeCircadianSwitchingKalman(records, extraDays = 7, config = testConfig())
        val boundary = full.changePoints.first().date
        val prefixByDate = prefix.days.filterNot { it.isGap || it.isForecast }.associateBy { it.date }
        val fullByDate = full.days.filterNot { it.isGap || it.isForecast }.associateBy { it.date }
        val maximumRevision = prefixByDate.keys.filter { it < boundary }.maxOf { date ->
            abs(circularDifference(midpoint(fullByDate.getValue(date)), midpoint(prefixByDate.getValue(date))))
        }
        val forecastDrift = full.days.filter { it.isForecast }.map { it.localDrift }.average()

        assertTrue("pre-boundary revision was $maximumRevision h", maximumRevision < 0.5)
        assertTrue("forecast retained drift $forecastDrift", abs(forecastDrift) < 0.25)
    }
}

private fun testConfig() = SwitchingKalmanConfig(
    processPhaseVariance = 0.12,
    processDriftVariance = 0.0005,
    measurementVarianceAtUnitWeight = 2.0,
    regimePriorDays = 60.0,
    regimeMinEvidence = 5.0,
    driftResetVariance = 1.0,
    offsetResetVariance = 9.0,
    changeCommitProbability = 0.80,
)

private fun syntheticStep(before: Double, after: Double, transitionDay: Int, totalDays: Int): List<SleepRecord> =
    generateSyntheticRecords(
        options(
            days = totalDays,
            tau = before,
            seed = 17,
            tauSegments = listOf(
                TauSegment(transitionDay, before),
                TauSegment(totalDays, after),
            ),
        ),
    )

private fun options(
    days: Int,
    tau: Double,
    seed: Int,
    tauSegments: List<TauSegment> = emptyList(),
) = SyntheticOptions(
    days = days,
    tau = tau,
    noise = 0.12,
    seed = seed,
    quality = 1.0,
    startDate = START_DATE,
    tauSegments = tauSegments,
)

private fun observations(records: List<SleepRecord>): List<KalmanObservation> {
    val firstDate = records.first().dateOfSleep
    val firstDateMs = firstDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    return prepareKalmanAnchors(records, firstDate, firstDateMs)
        .map { KalmanObservation(it.dayNumber, it.midpointHour, it.weight) }
}

private fun registryAnalysis(records: List<SleepRecord>): SwitchingKalmanAnalysis {
    val algorithm = CircadianAlgorithmRegistry.algorithm(CircadianAlgorithmRegistry.SWITCHING_KALMAN_ID)
    return algorithm.analyze(
        records = records,
        extraDays = 0,
        values = CircadianAlgorithmRegistry.resolvedValues(algorithm.id, emptyMap()),
    ) as SwitchingKalmanAnalysis
}

private fun shift(record: SleepRecord, hours: Double): SleepRecord {
    val millis = (hours * 3_600_000.0).toLong()
    return record.copy(startTime = record.startTime.plusMillis(millis), endTime = record.endTime.plusMillis(millis))
}

private fun dayDistance(a: LocalDate, b: LocalDate): Long = abs(Duration.between(a.atStartOfDay(), b.atStartOfDay()).toDays())

private fun midpoint(day: one.aozora.darkhour.core.circadian.CircadianDay): Double =
    (day.nightStartHour + day.nightEndHour) / 2.0

private fun circularDifference(actual: Double, expected: Double): Double {
    var difference = actual - expected
    while (difference > 12.0) difference -= 24.0
    while (difference <= -12.0) difference += 24.0
    return difference
}

private val START_DATE = LocalDate.parse("2025-01-01")
