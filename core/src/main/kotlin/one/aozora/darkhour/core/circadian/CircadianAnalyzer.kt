package one.aozora.darkhour.core.circadian

import one.aozora.darkhour.core.circadian.csf.CsfAnalysis
import one.aozora.darkhour.core.circadian.csf.CsfConfig
import one.aozora.darkhour.core.circadian.csf.analyzeCircadianCsf
import one.aozora.darkhour.core.model.SleepRecord

object CircadianAnalyzer {
    fun analyze(
        records: List<SleepRecord>,
        extraDays: Int = 0,
        config: CsfConfig = CsfConfig.Default,
    ): CsfAnalysis = analyzeCircadianCsf(records, extraDays, config)
}
