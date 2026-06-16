package one.aozora.darkhour

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import one.aozora.darkhour.data.HealthConnectDataController
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.DarkHourApp
import one.aozora.darkhour.ui.theme.DarkHourTheme

class MainActivity : ComponentActivity() {
    private lateinit var healthConnect: HealthConnectDataController
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) {
        healthConnect.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnect = HealthConnectDataController(this, lifecycleScope)
        enableEdgeToEdge()
        setContent {
            val healthState by healthConnect.state.collectAsState()
            DarkHourTheme {
                DarkHourApp(
                    records = healthState.records,
                    healthConnectAccess = healthState.access,
                    healthDataRange = healthState.dataRange,
                    hasHistoryPermission = healthState.hasHistoryPermission,
                    isRefreshing = healthState.isRefreshing,
                    importError = healthState.errorMessage,
                    onRequestHealthPermissions = {
                        requestHealthPermissions.launch(healthConnect.requiredPermissions())
                    },
                    onRequestHistoryPermission = {
                        requestHealthPermissions.launch(
                            healthConnect.requiredPermissions(HealthDataRange.ENTIRE_HISTORY),
                        )
                    },
                    onHealthDataRangeChange = { range ->
                        healthConnect.setDataRange(range)
                        if (
                            range == HealthDataRange.ENTIRE_HISTORY &&
                            range != healthState.dataRange
                        ) {
                            requestHealthPermissions.launch(healthConnect.requiredPermissions(range))
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::healthConnect.isInitialized) {
            healthConnect.refresh()
        }
    }
}
