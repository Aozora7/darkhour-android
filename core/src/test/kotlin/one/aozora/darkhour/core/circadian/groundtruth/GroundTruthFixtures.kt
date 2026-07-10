package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.model.SleepRecord
import java.io.BufferedReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Compact, manually annotated sleep-timing fixtures distilled from the
 * JavaScript project's Fitbit exports.  Stage intervals are deliberately
 * omitted because circadian estimators consume session-level timing only.
 */
data class GroundTruthDataset(
    val id: String,
    /** Browser time zone in which the manual overlay was authored. */
    val annotationZone: ZoneId,
    val records: List<SleepRecord>,
    val overlay: List<GroundTruthOverlayDay>,
    val controlPoints: List<GroundTruthControlPoint>,
)

data class GroundTruthOverlayDay(
    val date: LocalDate,
    val nightStartHour: Double,
    val nightEndHour: Double,
)

data class GroundTruthControlPoint(
    val date: LocalDate,
    /** Unwrapped hour as entered in the original manual overlay editor. */
    val midpointHour: Double,
)

object GroundTruthFixtures {
    private const val ROOT = "circadian/ground_truth"

    /** Real-data fixtures are intentionally optional and are never required in CI. */
    val isAvailable: Boolean
        get() = GroundTruthFixtures::class.java.classLoader.getResource("$ROOT/datasets.tsv") != null

    fun loadAll(): List<GroundTruthDataset> = descriptors().map(::load)

    fun load(id: String): GroundTruthDataset = load(descriptors().single { it.id == id })

    private fun load(descriptor: FixtureDescriptor): GroundTruthDataset = GroundTruthDataset(
        id = descriptor.id,
        annotationZone = descriptor.annotationZone,
        records = resourceLines("$ROOT/${descriptor.id}.sleep.tsv")
            .filterNot { it.startsWith("dateOfSleep\t") }
            .mapIndexed { index, row -> parseSleepRecord(index, row, descriptor.annotationZone) },
        overlay = resourceLines("$ROOT/${descriptor.id}.overlay.tsv")
            .filterNot { it.startsWith("date\t") }
            .map(::parseOverlayDay),
        controlPoints = resourceLines("$ROOT/${descriptor.id}.control-points.tsv")
            .filterNot { it.startsWith("date\t") }
            .map(::parseControlPoint),
    )

    private fun descriptors(): List<FixtureDescriptor> = resourceLines("$ROOT/datasets.tsv")
        .filterNot { it == "id\tannotationZone" }
        .map { row ->
            val fields = row.split('\t')
            require(fields.size == 2) { "Expected id and annotation zone: $row" }
            FixtureDescriptor(fields[0], ZoneId.of(fields[1]))
        }

    private fun resourceLines(path: String): List<String> = requireNotNull(
        GroundTruthFixtures::class.java.classLoader.getResourceAsStream(path),
    ) { "Missing ground-truth resource: $path" }.bufferedReader().use(BufferedReader::readLines)

    private fun parseSleepRecord(index: Int, row: String, annotationZone: ZoneId): SleepRecord {
        val fields = row.split('\t')
        require(fields.size == 10) { "Expected 10 columns in sleep fixture row: $row" }
        val startTime = Instant.parse(fields[1])
        val endTime = Instant.parse(fields[2])
        return SleepRecord(
            // Fitbit log IDs exceed the exact-integer range of JavaScript.  A
            // stable fixture-local ID is all a circadian estimator requires.
            logId = index.toLong() + 1,
            dateOfSleep = LocalDate.parse(fields[0]),
            startTime = startTime,
            endTime = endTime,
            durationMs = fields[3].toLong(),
            durationHours = fields[4].toDouble(),
            efficiency = fields[5].toInt(),
            minutesAsleep = fields[6].toInt(),
            minutesAwake = fields[7].toInt(),
            isMainSleep = fields[8].toBooleanStrict(),
            sleepScore = fields[9].takeIf(String::isNotEmpty)?.toDouble(),
            // The JavaScript implementation anchored all manually edited
            // overlays to the browser's Riga clock.  Keeping the raw instant
            // plus this offset recreates that coordinate system in Kotlin.
            startZoneOffset = annotationZone.rules.getOffset(startTime),
            endZoneOffset = annotationZone.rules.getOffset(endTime),
        )
    }

    private fun parseOverlayDay(row: String): GroundTruthOverlayDay {
        val fields = row.split('\t')
        require(fields.size == 3) { "Expected 3 columns in overlay fixture row: $row" }
        return GroundTruthOverlayDay(LocalDate.parse(fields[0]), fields[1].toDouble(), fields[2].toDouble())
    }

    private fun parseControlPoint(row: String): GroundTruthControlPoint {
        val fields = row.split('\t')
        require(fields.size == 2) { "Expected 2 columns in control-point fixture row: $row" }
        return GroundTruthControlPoint(LocalDate.parse(fields[0]), fields[1].toDouble())
    }

    private data class FixtureDescriptor(val id: String, val annotationZone: ZoneId)
}
