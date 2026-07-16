package one.aozora.darkhour.data

import androidx.health.connect.client.records.metadata.Metadata

data class SleepRecordDisplayMetadata(
    val title: String? = null,
    val notes: String? = null,
    val sourceName: String? = null,
    val sourceDevice: String? = null,
    val recordingMethod: SleepRecordingMethod = SleepRecordingMethod.UNKNOWN,
)

enum class SleepRecordingMethod {
    AUTOMATIC,
    ACTIVE,
    MANUAL,
    UNKNOWN,
}

internal fun Iterable<ImportedSleepRecord>.displayMetadataByLogId(
    packageDisplayName: (String) -> String,
): Map<Long, SleepRecordDisplayMetadata> {
    val records = toList()
    val sourceNames = records
        .mapNotNull(ImportedSleepRecord::sourcePackageName)
        .distinct()
        .associateWith(packageDisplayName)
    return records.associate { imported ->
        imported.record.logId to imported.toDisplayMetadata(
            sourceName = imported.sourcePackageName?.let(sourceNames::get),
        )
    }
}

private fun ImportedSleepRecord.toDisplayMetadata(
    sourceName: String?,
): SleepRecordDisplayMetadata = SleepRecordDisplayMetadata(
    title = title.normalizedDisplayText(),
    notes = notes.normalizedDisplayText(),
    sourceName = sourceName.normalizedDisplayText(),
    sourceDevice = sourceDevice.normalizedDisplayText(),
    recordingMethod = when (sourceRecordingMethod) {
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> SleepRecordingMethod.AUTOMATIC
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> SleepRecordingMethod.ACTIVE
        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> SleepRecordingMethod.MANUAL
        else -> SleepRecordingMethod.UNKNOWN
    },
)

private fun String?.normalizedDisplayText(): String? = this?.trim()?.takeIf(String::isNotEmpty)
