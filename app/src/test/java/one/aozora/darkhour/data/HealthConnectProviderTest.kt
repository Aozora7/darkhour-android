package one.aozora.darkhour.data

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectProviderTest {
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
}
