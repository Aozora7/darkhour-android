package one.aozora.darkhour

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import one.aozora.darkhour.data.HealthConnectDataController
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.ui.AppSettingsStore
import one.aozora.darkhour.ui.DarkHourApp
import one.aozora.darkhour.ui.DemoData
import one.aozora.darkhour.ui.theme.DarkHourTheme

class MainActivity : ComponentActivity() {
    private lateinit var healthConnect: HealthConnectDataController
    private lateinit var appSettings: AppSettingsStore
    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectDataController.permissionContract,
    ) {
        healthConnect.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnect = HealthConnectDataController(this, lifecycleScope)
        appSettings = AppSettingsStore(this)
        enableEdgeToEdge()
        setContent {
            val healthState by healthConnect.state.collectAsState()
            val initialSettings = remember { appSettings.read() }
            val initialDisplayOptions = remember { appSettings.readDisplayOptions() }
            val initialScheduleEntries = remember { appSettings.readScheduleEntries() }
            val records = if (BuildConfig.USE_DEMO_DATA) DemoData.records else healthState.records
            val healthConnectAccess = if (BuildConfig.USE_DEMO_DATA) {
                one.aozora.darkhour.data.HealthConnectAccess.CONNECTED
            } else {
                healthState.access
            }
            DarkHourTheme {
                DarkHourApp(
                    records = records,
                    initialSettings = initialSettings,
                    onAppSettingsChange = appSettings::write,
                    initialDisplayOptions = initialDisplayOptions,
                    onDisplayOptionsChange = appSettings::writeDisplayOptions,
                    initialScheduleEntries = initialScheduleEntries,
                    onScheduleEntriesChange = appSettings::writeScheduleEntries,
                    healthConnectAccess = healthConnectAccess,
                    healthDataRange = healthState.dataRange,
                    hasHistoryPermission = BuildConfig.USE_DEMO_DATA || healthState.hasHistoryPermission,
                    isRefreshing = !BuildConfig.USE_DEMO_DATA && healthState.isRefreshing,
                    importError = if (BuildConfig.USE_DEMO_DATA) null else healthState.errorMessage,
                    onRequestHealthPermissions = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            requestHealthPermissions.launch(healthConnect.requiredPermissions())
                        }
                    },
                    onRequestHistoryPermission = {
                        if (!BuildConfig.USE_DEMO_DATA) {
                            requestHealthPermissions.launch(
                                healthConnect.requiredPermissions(HealthDataRange.ENTIRE_HISTORY),
                            )
                        }
                    },
                    onHealthDataRangeChange = { range ->
                        if (!BuildConfig.USE_DEMO_DATA) {
                            healthConnect.setDataRange(range)
                            if (
                                range == HealthDataRange.ENTIRE_HISTORY &&
                                range != healthState.dataRange
                            ) {
                                requestHealthPermissions.launch(healthConnect.requiredPermissions(range))
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::healthConnect.isInitialized && !BuildConfig.USE_DEMO_DATA) {
            healthConnect.refresh()
        }
    }
}
