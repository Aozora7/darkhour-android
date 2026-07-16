package one.aozora.darkhour.ui.settings

import one.aozora.darkhour.data.HealthConnectFileOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSectionsTest {
    @Test
    fun exportProgressIsAvailableWithoutFileImportSupport() {
        assertEquals(
            "Preparing sleep export…",
            fileOperationProgressLabel(
                operation = HealthConnectFileOperation.PREPARING_EXPORT,
                fileWriteSupported = false,
            ),
        )
        assertEquals(
            "Exporting sleep records…",
            fileOperationProgressLabel(
                operation = HealthConnectFileOperation.EXPORTING,
                fileWriteSupported = false,
            ),
        )
    }

    @Test
    fun importProgressRequiresFileImportSupport() {
        assertNull(
            fileOperationProgressLabel(
                operation = HealthConnectFileOperation.IMPORTING,
                fileWriteSupported = false,
            ),
        )
        assertEquals(
            "Importing sleep files…",
            fileOperationProgressLabel(
                operation = HealthConnectFileOperation.IMPORTING,
                fileWriteSupported = true,
            ),
        )
    }

    @Test
    fun idleHasNoProgressLabel() {
        assertNull(
            fileOperationProgressLabel(
                operation = HealthConnectFileOperation.IDLE,
                fileWriteSupported = true,
            ),
        )
    }
}
