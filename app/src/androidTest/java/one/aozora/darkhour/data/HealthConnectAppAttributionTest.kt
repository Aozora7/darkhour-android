package one.aozora.darkhour.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HealthConnectAppAttributionTest {
    @Test
    fun discoversApplicationLabelThroughHealthConnectRationaleIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedLabel = context.packageManager
            .getApplicationLabel(context.applicationInfo)
            .toString()

        val appNames = healthConnectAppDisplayNames(context)
        assertEquals(
            expectedLabel,
            appNames[context.packageName],
        )
        InstrumentationRegistry.getArguments()
            .getString(SOURCE_PACKAGE_ARGUMENT)
            ?.let { sourcePackageName ->
                val sourceName = appNames[sourcePackageName]
                assertNotNull(sourceName)
                assertNotEquals(sourcePackageName, sourceName)
            }
    }

    private companion object {
        const val SOURCE_PACKAGE_ARGUMENT = "sourcePackage"
    }
}
