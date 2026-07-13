package one.aozora.darkhour.core.circadian.groundtruth

import one.aozora.darkhour.core.circadian.CircadianAlgorithmParameters
import java.io.StringReader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

internal fun updateParameterDefaultsResource(
    path: Path,
    algorithmId: String,
    values: Map<String, Double>,
): Set<String> {
    val original = Files.readString(path, UTF_8)
    val updated = updateParameterDefaults(original, algorithmId, values)
    if (updated.content != original) replaceAtomically(path, updated.content)
    return updated.changedKeys
}

internal data class ParameterDefaultsUpdate(
    val content: String,
    val changedKeys: Set<String>,
)

internal fun updateParameterDefaults(
    content: String,
    algorithmId: String,
    values: Map<String, Double>,
): ParameterDefaultsUpdate {
    require(values.isNotEmpty()) { "No tuned parameter values supplied for $algorithmId" }

    val parsed = CircadianAlgorithmParameters.parse(StringReader(content))
    val parameters = checkNotNull(parsed[algorithmId]) {
        "No parameters configured for circadian algorithm: $algorithmId"
    }.associateBy { it.key }
    require(values.keys.all(parameters::containsKey)) {
        "Unknown parameters for $algorithmId: ${values.keys - parameters.keys}"
    }

    val newline = if (content.contains("\r\n")) "\r\n" else "\n"
    val hasTrailingNewline = content.endsWith(newline)
    val body = if (hasTrailingNewline) content.dropLast(newline.length) else content
    val changedKeys = linkedSetOf<String>()
    val updatedLines = body.split(newline).map { line ->
        if (line.isBlank() || line.startsWith('#')) return@map line

        val columns = line.split('\t').toMutableList()
        val key = columns[1]
        val value = values[key]
        if (columns[0] != algorithmId || value == null) return@map line
        if (columns[3].toDouble() == value) return@map line

        columns[3] = formatDefault(value, parameters.getValue(key).decimalPlaces)
        changedKeys += key
        columns.joinToString("\t")
    }
    val updatedContent = updatedLines.joinToString(newline) + if (hasTrailingNewline) newline else ""

    val reparsed = CircadianAlgorithmParameters.parse(StringReader(updatedContent))
    val updatedParameters = reparsed.getValue(algorithmId).associateBy { it.key }
    values.forEach { (key, value) ->
        check(updatedParameters.getValue(key).defaultValue == value) {
            "Failed to update $algorithmId parameter '$key'"
        }
    }
    return ParameterDefaultsUpdate(updatedContent, changedKeys)
}

private fun formatDefault(value: Double, decimalPlaces: Int): String =
    String.format(Locale.ROOT, "%.${decimalPlaces}f", if (value == 0.0) 0.0 else value)

private fun replaceAtomically(path: Path, content: String) {
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content, UTF_8)
        try {
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
