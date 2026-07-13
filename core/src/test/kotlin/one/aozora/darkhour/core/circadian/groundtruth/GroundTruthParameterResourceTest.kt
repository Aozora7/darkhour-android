package one.aozora.darkhour.core.circadian.groundtruth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthParameterResourceTest {

    @Test
    fun updatesOnlySelectedDefaultsAndPreservesCrlf() {
        val content = HEADER + "\r\n" +
            "first-v1\talpha\tAlpha\t1.00\t0.0\t2.0\t20\t2\r\n" +
            "first-v1\tbeta\tBeta\t3.0\t1.0\t5.0\t4\t1\th\r\n" +
            "second-v1\talpha\tAlpha\t1.00\t0.0\t2.0\t20\t2\r\n"

        val updated = updateParameterDefaults(content, "first-v1", mapOf("alpha" to 1.25))

        assertEquals(setOf("alpha"), updated.changedKeys)
        assertEquals(
            HEADER + "\r\n" +
                "first-v1\talpha\tAlpha\t1.25\t0.0\t2.0\t20\t2\r\n" +
                "first-v1\tbeta\tBeta\t3.0\t1.0\t5.0\t4\t1\th\r\n" +
                "second-v1\talpha\tAlpha\t1.00\t0.0\t2.0\t20\t2\r\n",
            updated.content,
        )
    }

    @Test
    fun keepsExistingFormattingWhenTheDefaultIsUnchanged() {
        val content = HEADER + "\nfirst-v1\talpha\tAlpha\t1.00\t0.0\t2.0\t20\t2\n"

        val updated = updateParameterDefaults(content, "first-v1", mapOf("alpha" to 1.0))

        assertTrue(updated.changedKeys.isEmpty())
        assertEquals(content, updated.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownParameters() {
        val content = HEADER + "\nfirst-v1\talpha\tAlpha\t1.00\t0.0\t2.0\t20\t2\n"

        updateParameterDefaults(content, "first-v1", mapOf("missing" to 1.0))
    }

    private companion object {
        const val HEADER = "# algorithm_id\tkey\tlabel\tdefault\tminimum\tmaximum\tsteps\tdecimal_places\tunit"
    }
}
