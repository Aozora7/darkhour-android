package one.aozora.darkhour.core.circadian

import java.io.Reader

internal object CircadianAlgorithmParameters {
    private const val RESOURCE_PATH =
        "/one/aozora/darkhour/core/circadian/circadian-algorithm-parameters.tsv"

    private val parametersByAlgorithmId: Map<String, List<CircadianNumericParameter>> by lazy {
        val stream = checkNotNull(CircadianAlgorithmParameters::class.java.getResourceAsStream(RESOURCE_PATH)) {
            "Missing circadian algorithm parameter resource: $RESOURCE_PATH"
        }
        stream.bufferedReader(Charsets.UTF_8).use(::parse)
    }

    fun forAlgorithm(algorithmId: String): List<CircadianNumericParameter> =
        checkNotNull(parametersByAlgorithmId[algorithmId]) {
            "No parameters configured for circadian algorithm: $algorithmId"
        }

    internal fun parse(reader: Reader): Map<String, List<CircadianNumericParameter>> {
        val parameters = linkedMapOf<String, MutableList<CircadianNumericParameter>>()
        val keysByAlgorithm = mutableMapOf<String, MutableSet<String>>()

        reader.buffered().lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            if (rawLine.isBlank() || rawLine.startsWith('#')) return@forEachIndexed

            val columns = rawLine.split('\t')
            require(columns.size in REQUIRED_COLUMN_COUNT..COLUMN_COUNT_WITH_UNIT) {
                "Invalid circadian parameter at line $lineNumber: expected $REQUIRED_COLUMN_COUNT or " +
                    "$COLUMN_COUNT_WITH_UNIT tab-separated columns"
            }

            val algorithmId = columns[0].required("algorithm id", lineNumber)
            val key = columns[1].required("parameter key", lineNumber)
            require(keysByAlgorithm.getOrPut(algorithmId, ::mutableSetOf).add(key)) {
                "Duplicate circadian parameter '$key' for '$algorithmId' at line $lineNumber"
            }

            val parameter = CircadianNumericParameter(
                key = key,
                label = columns[2].required("label", lineNumber),
                defaultValue = columns[3].toDoubleValue("default", lineNumber),
                minValue = columns[4].toDoubleValue("minimum", lineNumber),
                maxValue = columns[5].toDoubleValue("maximum", lineNumber),
                steps = columns[6].toIntValue("steps", lineNumber),
                decimalPlaces = columns[7].toIntValue("decimal places", lineNumber),
                unit = columns.getOrElse(8) { "" },
            )
            parameters.getOrPut(algorithmId, ::mutableListOf).add(parameter)
        }

        require(parameters.isNotEmpty()) { "Circadian algorithm parameter resource is empty" }
        return parameters.mapValues { (_, values) -> values.toList() }
    }

    private fun String.required(name: String, lineNumber: Int): String =
        also { require(isNotBlank()) { "Missing $name at line $lineNumber" } }

    private fun String.toDoubleValue(name: String, lineNumber: Int): Double =
        toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: throw IllegalArgumentException("Invalid $name at line $lineNumber: '$this'")

    private fun String.toIntValue(name: String, lineNumber: Int): Int =
        toIntOrNull() ?: throw IllegalArgumentException("Invalid $name at line $lineNumber: '$this'")

    private const val REQUIRED_COLUMN_COUNT = 8
    private const val COLUMN_COUNT_WITH_UNIT = 9
}
