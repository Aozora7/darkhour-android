package one.aozora.darkhour.data

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectProviderTest {
    @Test
    fun historyPermissionStateDistinguishesGrantAvailabilityAndUnavailableFeature() {
        assertEquals(
            HistoryPermissionState.GRANTED,
            historyPermissionState(
                setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY),
                featureAvailable = false,
            ),
        )
        assertEquals(
            HistoryPermissionState.AVAILABLE_NOT_GRANTED,
            historyPermissionState(emptySet(), featureAvailable = true),
        )
        assertEquals(
            HistoryPermissionState.UNAVAILABLE,
            historyPermissionState(emptySet(), featureAvailable = false),
        )
    }

    @Test
    fun missingProviderRequiresInstallation() {
        assertEquals(
            HealthConnectAccess.INSTALL_REQUIRED,
            healthConnectAccess(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                providerInstalled = false,
            ),
        )
    }

    @Test
    fun installedProviderThatCannotServeSdkRequiresUpdate() {
        assertEquals(
            HealthConnectAccess.UPDATE_REQUIRED,
            healthConnectAccess(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                providerInstalled = true,
            ),
        )
    }

    @Test
    fun providerIsUsableOnlyForConnectedAndPermissionStates() {
        assertEquals(true, HealthConnectAccess.CONNECTED.providerAvailable)
        assertEquals(true, HealthConnectAccess.PERMISSION_REQUIRED.providerAvailable)
        assertEquals(false, HealthConnectAccess.SETUP_REQUIRED.providerAvailable)
        assertEquals(false, HealthConnectAccess.INSTALL_REQUIRED.providerAvailable)
        assertEquals(false, HealthConnectAccess.UPDATE_REQUIRED.providerAvailable)
        assertEquals(false, HealthConnectAccess.UNAVAILABLE.providerAvailable)
    }

    @Test
    fun emptyPermissionResultOffersSetupOnlyBeforeSetupRetry() {
        assertEquals(
            true,
            shouldOfferHealthConnectSetup(
                grantedPermissions = emptySet(),
                providerAvailable = true,
                resumedAfterSetup = false,
            ),
        )
        assertEquals(
            false,
            shouldOfferHealthConnectSetup(
                grantedPermissions = emptySet(),
                providerAvailable = true,
                resumedAfterSetup = true,
            ),
        )
    }

    @Test
    fun directImportCompatibilityIsDebugOptInForAndroidNineThroughThirteen() {
        assertEquals(false, isLegacyDirectFileImportSupported(false, Build.VERSION_CODES.P))
        assertEquals(false, isLegacyDirectFileImportSupported(true, Build.VERSION_CODES.O_MR1))
        assertEquals(true, isLegacyDirectFileImportSupported(true, Build.VERSION_CODES.P))
        assertEquals(true, isLegacyDirectFileImportSupported(true, Build.VERSION_CODES.TIRAMISU))
        assertEquals(
            false,
            isLegacyDirectFileImportSupported(true, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    @Test
    fun historyCapabilityEnablesNormalFileManagementOnAndroidNineThroughThirteen() {
        val available = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            historyPermissionState = HistoryPermissionState.AVAILABLE_NOT_GRANTED,
            allowLegacyFileImport = false,
        )
        assertEquals(true, available.importSupported)
        assertEquals(true, available.deletionSupported)
        assertEquals(false, available.usesLegacyDirectImport)
        assertEquals(true, available.importRequiresHistoryPermission)
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getWritePermission(SleepSessionRecord::class),
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            ),
            sleepFileImportPermissions(available),
        )

        val granted = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.P,
            historyPermissionState = HistoryPermissionState.GRANTED,
            allowLegacyFileImport = true,
        )
        assertEquals(true, granted.importSupported)
        assertEquals(true, granted.deletionSupported)
        assertEquals(false, granted.usesLegacyDirectImport)
        assertEquals(true, granted.importRequiresHistoryPermission)
    }

    @Test
    fun unavailableHistoryRetainsOnlyDebugDirectImportOnLegacyAndroid() {
        val production = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
            allowLegacyFileImport = false,
        )
        assertEquals(false, production.importSupported)
        assertEquals(false, production.deletionSupported)

        val debug = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
            allowLegacyFileImport = true,
        )
        assertEquals(true, debug.importSupported)
        assertEquals(false, debug.deletionSupported)
        assertEquals(true, debug.usesLegacyDirectImport)
        assertEquals(
            setOf(HealthPermission.getWritePermission(SleepSessionRecord::class)),
            sleepFileImportPermissions(debug),
        )
    }

    @Test
    fun androidFourteenRequiresHistoryCapabilityButNotHistoryGrantForImport() {
        val unavailable = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            historyPermissionState = HistoryPermissionState.UNAVAILABLE,
            allowLegacyFileImport = false,
        )
        assertEquals(false, unavailable.importSupported)
        assertEquals(false, unavailable.deletionSupported)

        val capabilities = sleepFileCapabilities(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            historyPermissionState = HistoryPermissionState.AVAILABLE_NOT_GRANTED,
            allowLegacyFileImport = false,
        )
        assertEquals(true, capabilities.importSupported)
        assertEquals(true, capabilities.deletionSupported)
        assertEquals(false, capabilities.importRequiresHistoryPermission)
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getWritePermission(SleepSessionRecord::class),
            ),
            sleepFileImportPermissions(capabilities),
        )
    }
}
