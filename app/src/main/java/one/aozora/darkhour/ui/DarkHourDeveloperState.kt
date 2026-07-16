package one.aozora.darkhour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.aozora.darkhour.core.circadian.CircadianAlgorithmRegistry
import one.aozora.darkhour.core.model.SleepRecord

internal data class DeveloperCircadianSession(
    val state: DeveloperCircadianState,
    val injectedRecords: List<SleepRecord>,
)

@Composable
internal fun rememberDeveloperCircadianSession(
    records: List<SleepRecord>,
    isDebug: Boolean,
): DeveloperCircadianSession {
    var algorithmId by remember {
        mutableStateOf(initialDeveloperAlgorithmId(isDebug))
    }
    var overridesByAlgorithm by remember {
        mutableStateOf<Map<String, Map<String, Double>>>(emptyMap())
    }
    var injectedRecords by remember { mutableStateOf<List<SleepRecord>>(emptyList()) }
    var injectionForm by remember { mutableStateOf(defaultDebugSleepInjectionForm(records)) }
    var injectionError by remember { mutableStateOf<String?>(null) }
    var injectionEdited by remember { mutableStateOf(false) }

    LaunchedEffect(records) {
        if (injectedRecords.isEmpty() && !injectionEdited) {
            injectionForm = defaultDebugSleepInjectionForm(records)
        }
    }

    fun addSleepRecords() {
        if (!isDebug) return
        generateDebugSleepRecords(
            form = injectionForm,
            existingInjectedCount = injectedRecords.size,
        ).fold(
            onSuccess = { result ->
                injectedRecords = injectedRecords + result.records
                injectionForm = result.nextForm
                injectionError = null
                injectionEdited = true
            },
            onFailure = { error ->
                injectionError = error.message ?: "Invalid injection values"
            },
        )
    }

    fun clearSleepRecords() {
        injectedRecords = emptyList()
        injectionForm = defaultDebugSleepInjectionForm(records)
        injectionError = null
        injectionEdited = false
    }

    return DeveloperCircadianSession(
        state = DeveloperCircadianState(
            algorithmId = algorithmId,
            overridesByAlgorithm = overridesByAlgorithm,
            onAlgorithmChange = { selectedId ->
                algorithmId = CircadianAlgorithmRegistry.algorithm(selectedId).id
            },
            onParameterChange = { key, value ->
                overridesByAlgorithm = overridesByAlgorithm + (
                    algorithmId to (
                        overridesByAlgorithm[algorithmId].orEmpty() + (key to value)
                    )
                )
            },
            onParameterReset = { key ->
                val active = overridesByAlgorithm[algorithmId].orEmpty() - key
                overridesByAlgorithm = if (active.isEmpty()) {
                    overridesByAlgorithm - algorithmId
                } else {
                    overridesByAlgorithm + (algorithmId to active)
                }
            },
            sleepInjection = DeveloperSleepInjectionState(
                form = injectionForm,
                injectedRecordCount = injectedRecords.size,
                error = injectionError,
                onFormChange = { form ->
                    injectionForm = form
                    injectionError = null
                    injectionEdited = true
                },
                onAdd = ::addSleepRecords,
                onClear = ::clearSleepRecords,
            ),
        ),
        injectedRecords = injectedRecords,
    )
}

internal fun List<SleepRecord>.withDeveloperDisplayRecords(
    injectedRecords: List<SleepRecord>,
    isDebug: Boolean,
): List<SleepRecord> = if (isDebug && injectedRecords.isNotEmpty()) {
    (this + injectedRecords).sortedBy(SleepRecord::startTime)
} else {
    this
}

internal fun List<SleepRecord>.withDeveloperAnalysisRecords(
    injectedRecords: List<SleepRecord>,
    isDebug: Boolean,
): List<SleepRecord> = if (isDebug && injectedRecords.isNotEmpty()) {
    (this + injectedRecords)
        .distinctBy(SleepRecord::logId)
        .sortedBy(SleepRecord::startTime)
} else {
    this
}

internal fun initialDeveloperAlgorithmId(isDebug: Boolean): String =
    CircadianAlgorithmRegistry.defaultAlgorithm.id
