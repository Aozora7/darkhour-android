package one.aozora.darkhour.data

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
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
}
