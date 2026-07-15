package one.aozora.darkhour.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal data class JsonSleepRecordShape(
    val recordKeys: Set<String>,
    val nestedSleepKeys: Set<String>,
)

internal fun detectJsonSleepRecordShape(input: InputStream): JsonSleepRecordShape? =
    runCatching {
        input.jsonReader().use { reader ->
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> reader.readFirstSleepRecordShapeFromArray()
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() != "sleep") {
                            reader.skipValue()
                            continue
                        }
                        return@use reader.readFirstSleepRecordShapeFromArray()
                    }
                    null
                }
                else -> null
            }
        }
    }.getOrNull()

private fun JsonReader.readFirstSleepRecordShapeFromArray(): JsonSleepRecordShape? {
    if (peek() != JsonToken.BEGIN_ARRAY) return null
    beginArray()
    if (!hasNext() || peek() != JsonToken.BEGIN_OBJECT) return null
    val recordKeys = mutableSetOf<String>()
    val nestedSleepKeys = mutableSetOf<String>()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        recordKeys += name
        if (name == "sleep" && peek() == JsonToken.BEGIN_OBJECT) {
            beginObject()
            while (hasNext()) {
                nestedSleepKeys += nextName()
                skipValue()
            }
            endObject()
        } else {
            skipValue()
        }
    }
    return JsonSleepRecordShape(recordKeys, nestedSleepKeys)
}

internal fun InputStream.jsonReader(): JsonReader =
    JsonReader(InputStreamReader(this, StandardCharsets.UTF_8))

internal fun JsonReader.nextStringOrNull(): String? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }
    JsonToken.STRING,
    JsonToken.NUMBER,
    JsonToken.BOOLEAN,
    -> nextString()
    else -> {
        skipValue()
        null
    }
}

internal fun JsonReader.nextLongOrNull(): Long? =
    nextStringOrNull()?.toLongOrNull()

internal fun JsonReader.readStringObject(): Map<String, String?> {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return emptyMap()
    }
    return buildMap {
        beginObject()
        while (hasNext()) {
            put(nextName(), nextStringOrNull())
        }
        endObject()
    }
}
