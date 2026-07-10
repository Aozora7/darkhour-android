package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.model.SleepRecord

object CircadianAnalyzer {
    fun analyze(
        records: List<SleepRecord>,
        extraDays: Int = 0,
        algorithmId: String = CircadianAlgorithmRegistry.defaultAlgorithm.id,
        overrides: Map<String, Double> = emptyMap(),
    ): CircadianAnalysis = CircadianAlgorithmRegistry.analyze(records, extraDays, algorithmId, overrides)
}
