package one.aozora.darkhour.ui.settings

import android.os.Build
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

    @Test
    fun missingImportPermissionsExplainTheDialogSequence() {
        assertEquals(
            "Import requires sleep read, past-data, and sleep write access. Permissions will " +
                "be requested before import.",
            fileImportPermissionNotice(
                permissionsGranted = false,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                usesLegacyDirectImport = false,
            ),
        )
        assertEquals(
            "Import requires sleep read and sleep write access. Permissions will be requested " +
                "before import.",
            fileImportPermissionNotice(
                permissionsGranted = false,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                usesLegacyDirectImport = false,
            ),
        )
        assertEquals(
            "Import requires sleep write access. Permission will be requested before import.",
            fileImportPermissionNotice(
                permissionsGranted = false,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                usesLegacyDirectImport = true,
            ),
        )
        assertNull(
            fileImportPermissionNotice(
                permissionsGranted = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                usesLegacyDirectImport = false,
            ),
        )
    }

    @Test
    fun unavailableHistoryCapabilitySuggestsTheApplicableUpdate() {
        assertEquals(
            "Import requires past data access missing in the current Health Connect provider. " +
                "Updating the Health Connect app may enable it.",
            fileImportUnsupportedMessage(Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            "Import requires past data access missing in the current Health Connect provider. " +
                "A Google Play system update may enable it.",
            fileImportUnsupportedMessage(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }
}
