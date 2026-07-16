package one.aozora.darkhour.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

internal fun healthConnectAppDisplayNames(context: Context): Map<String, String> {
    val packageManager = context.packageManager
    val appNames = linkedMapOf<String, String>()
    healthConnectAttributionIntents().forEach { intent ->
        packageManager.queryHealthConnectActivities(intent).forEach { resolveInfo ->
            val applicationInfo = resolveInfo.activityInfo.applicationInfo
            val packageName = applicationInfo.packageName
            val label = packageManager.getApplicationLabel(applicationInfo).toString()
            if (packageName.isNotBlank() && label.isNotBlank()) {
                appNames.putIfAbsent(packageName, label)
            }
        }
    }
    return appNames
}

private fun healthConnectAttributionIntents(): List<Intent> = listOf(
    Intent(HEALTH_CONNECT_RATIONALE_ACTION),
    Intent(VIEW_PERMISSION_USAGE_ACTION).addCategory(HEALTH_PERMISSIONS_CATEGORY),
)

private fun PackageManager.queryHealthConnectActivities(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }

private const val HEALTH_CONNECT_RATIONALE_ACTION =
    "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
private const val VIEW_PERMISSION_USAGE_ACTION = "android.intent.action.VIEW_PERMISSION_USAGE"
private const val HEALTH_PERMISSIONS_CATEGORY = "android.intent.category.HEALTH_PERMISSIONS"
