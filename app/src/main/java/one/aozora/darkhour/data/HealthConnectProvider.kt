package one.aozora.darkhour.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient

internal const val HEALTH_CONNECT_PROVIDER_PACKAGE_NAME =
    "com.google.android.apps.healthdata"

internal fun healthConnectAccess(
    context: Context,
    sdkStatus: Int = HealthConnectClient.getSdkStatus(context),
): HealthConnectAccess = healthConnectAccess(
    sdkStatus = sdkStatus,
    providerInstalled = context.isHealthConnectProviderInstalled(),
)

internal fun healthConnectAccess(
    sdkStatus: Int,
    providerInstalled: Boolean,
): HealthConnectAccess = when (sdkStatus) {
    HealthConnectClient.SDK_AVAILABLE -> HealthConnectAccess.CONNECTED
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
        if (providerInstalled) {
            HealthConnectAccess.UPDATE_REQUIRED
        } else {
            HealthConnectAccess.INSTALL_REQUIRED
        }
    }
    else -> HealthConnectAccess.UNAVAILABLE
}

internal fun shouldOfferHealthConnectSetup(
    grantedPermissions: Set<String>,
    providerAvailable: Boolean,
    resumedAfterSetup: Boolean,
): Boolean =
    grantedPermissions.isEmpty() && providerAvailable && !resumedAfterSetup

private fun Context.isHealthConnectProviderInstalled(): Boolean = try {
    @Suppress("DEPRECATION")
    packageManager.getPackageInfo(HEALTH_CONNECT_PROVIDER_PACKAGE_NAME, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
