package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange

@Composable
internal fun HealthConnectGate(
    access: HealthConnectAccess,
    dataRangeRequiresHistoryPermission: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("health_connect_gate"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when (access) {
                    HealthConnectAccess.PERMISSION_REQUIRED -> "Connect Health Connect"
                    HealthConnectAccess.UPDATE_REQUIRED -> "Update Health Connect"
                    HealthConnectAccess.UNAVAILABLE -> "Health Connect unavailable"
                    HealthConnectAccess.CONNECTED -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (access) {
                    HealthConnectAccess.PERMISSION_REQUIRED -> if (dataRangeRequiresHistoryPermission) {
                        "Allow sleep and history access to show your complete actogram."
                    } else {
                        "Allow sleep access to show your last 30 days in the actogram."
                    }
                    HealthConnectAccess.UPDATE_REQUIRED ->
                        "Install the available Health Connect update, then return to Dark Hour."
                    HealthConnectAccess.UNAVAILABLE ->
                        "This device does not provide Health Connect."
                    HealthConnectAccess.CONNECTED -> ""
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (access == HealthConnectAccess.PERMISSION_REQUIRED) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.testTag("request_health_permissions"),
                ) {
                    Text("Allow access")
                }
            }
        }
    }
}

@Composable
internal fun HistoryAccessCallout(
    dataRange: HealthDataRange,
    onAllowHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    historyAccessTitle(dataRange),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Allow history access to inspect older sleep records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onAllowHistory,
                modifier = Modifier.testTag("actogram_request_history_permission"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Allow")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("dismiss_history_callout"),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss history access message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun historyAccessTitle(dataRange: HealthDataRange): String =
    when (dataRange) {
        HealthDataRange.DefaultPeriod -> "Showing last 30 days"
        HealthDataRange.EntireHistory -> "History access required"
        is HealthDataRange.Custom -> "Showing last ${dataRange.days} days"
    }
