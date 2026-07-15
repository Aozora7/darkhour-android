package one.aozora.darkhour

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import one.aozora.darkhour.ui.DarkHourApp
import one.aozora.darkhour.ui.DemoData
import one.aozora.darkhour.ui.theme.DarkHourTheme
import one.aozora.darkhour.ui.settings.AppSettings
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
import one.aozora.darkhour.data.SleepExportPackage
import one.aozora.darkhour.data.SleepExportPreparation
import one.aozora.darkhour.data.SleepExportRange
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DarkHourAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomNavigationSelectsAllDestinations() {
        setContent()

        composeRule.onNodeWithTag("destination_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Circadian stats").assertIsDisplayed()
        composeRule.onNodeWithText("Time in bed per day").assertIsDisplayed()
        composeRule.onNodeWithText("Cumulative shift").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sleep score").assertCountEquals(0)
        composeRule.onAllNodesWithText("Confidence").assertCountEquals(0)
        composeRule.onAllNodesWithText("Anchors").assertCountEquals(0)

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("destination_actogram").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("actogram_canvas").assertIsDisplayed()
    }

    @Test
    fun pagerSwipeMovesToStats() {
        setContent()

        composeRule.onNodeWithTag("main_pager").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, centerY),
                end = Offset(width * 0.1f, centerY),
                durationMillis = 600,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Circadian stats").assertIsDisplayed()
    }

    @Test
    fun actogramUsesFloatingOptionsWithoutTopBar() {
        setContent()

        composeRule.onAllNodesWithTag("actogram_top_bar").assertCountEquals(0)
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule.onNodeWithTag("actogram_options").assertIsDisplayed()
    }

    @Test
    fun visualizationOptionsOpenInBottomSheet() {
        setContent()

        composeRule.onNodeWithTag("actogram_options").performClick()

        composeRule.onNodeWithTag("actogram_options_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Double plot").assertIsDisplayed()
        composeRule.onNodeWithTag("order_newest").assertIsSelected()
        composeRule.onNodeWithText("Vertical scale").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun debugCircadianToolsGenerateControlsForTheSelectedAlgorithm() {
        setContent()

        composeRule.onNodeWithTag("circadian_developer_tools").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("circadian_developer_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("circadian_parameter_tau_prior").assertIsDisplayed()
        composeRule.onNodeWithTag("circadian_reset_tau_prior").assertIsDisplayed()

        composeRule.onNodeWithTag("circadian_algorithm_unwrapped-kalman-v1").performClick()

        composeRule.onNodeWithTag("circadian_parameter_drift_prior").assertIsDisplayed()
        composeRule.onAllNodesWithTag("circadian_parameter_tau_prior").assertCountEquals(0)
    }

    @Test
    fun debugCircadianParameterResetRestoresTheDefaultValue() {
        setContent()

        composeRule.onNodeWithTag("circadian_developer_tools").performClick()
        composeRule.onNodeWithTag("circadian_reset_tau_prior").assertIsNotEnabled()

        composeRule.onNodeWithTag("circadian_parameter_tau_prior").performTouchInput {
            click(Offset(width * 0.2f, centerY))
        }
        composeRule.onNodeWithTag("circadian_reset_tau_prior").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("circadian_reset_tau_prior").assertIsNotEnabled()
    }

    @Test
    fun debugSleepInjectionHasSeparateTabAndSessionControls() {
        setContent()

        composeRule.onNodeWithTag("circadian_developer_tools").performClick()
        composeRule.onNodeWithTag("circadian_developer_tab_sleep_injection").performClick()

        composeRule.onNodeWithTag("sleep_injection_date").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_injection_time_from").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_injection_time_to").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_injection_drift").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_injection_days").assertIsDisplayed()
        composeRule.onNodeWithText("Injected records: 0").assertIsDisplayed()

        composeRule.onNodeWithTag("sleep_injection_add").performClick()
        composeRule.onNodeWithText("Injected records: 1").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_injection_clear").performClick()
        composeRule.onNodeWithText("Injected records: 0").assertIsDisplayed()
    }

    @Test
    fun visualizationOrderCanBeChanged() {
        setContent()

        composeRule.onNodeWithTag("actogram_options").performClick()
        composeRule.onNodeWithTag("order_oldest").performClick()

        composeRule.onNodeWithTag("order_oldest").assertIsSelected()
    }

    @Test
    fun isoDateTimeCanBeEnabledInSettings() {
        setContent()

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Dynamic color").assertCountEquals(0)
        composeRule.onNodeWithText("Example: Jun 4, 2026 12:34").assertIsDisplayed()

        composeRule.onNodeWithTag("iso_date_time_toggle").performClick()

        composeRule.onNodeWithText("Example: 2026-06-04 12:34").assertIsDisplayed()
    }

    @Test
    fun pinchChangesActogramVerticalScale() {
        setContent()
        val rowHeight = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Row height 22.0",
        )
        composeRule.onNodeWithTag("actogram_canvas").assert(rowHeight)

        composeRule.onNodeWithTag("actogram_canvas").performTouchInput {
            val center = this.center
            pinch(
                start0 = Offset(center.x - 30f, center.y),
                start1 = Offset(center.x + 30f, center.y),
                end0 = Offset(center.x - 90f, center.y),
                end1 = Offset(center.x + 90f, center.y),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("actogram_canvas").assert(
            SemanticsMatcher("row height changed") {
                it.config[SemanticsProperties.StateDescription] != "Row height 22.0"
            },
        )
    }

    @Test
    fun missingPermissionReplacesActogramWithPermissionOffer() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = emptyList(),
                    healthConnectAccess = HealthConnectAccess.PERMISSION_REQUIRED,
                )
            }
        }

        composeRule.onNodeWithTag("health_connect_gate").assertIsDisplayed()
        composeRule.onNodeWithTag("request_health_permissions").assertIsDisplayed()
        composeRule.onAllNodesWithTag("actogram_canvas").assertCountEquals(0)
    }

    @Test
    fun missingProviderOffersInstallation() {
        var installRequested = false
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = emptyList(),
                    healthConnectAccess = HealthConnectAccess.INSTALL_REQUIRED,
                    onInstallHealthConnect = { installRequested = true },
                )
            }
        }

        composeRule.onNodeWithTag("install_health_connect").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertTrue(installRequested) }
    }

    @Test
    fun missingProviderDisablesHealthConnectSettings() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = emptyList(),
                    healthConnectAccess = HealthConnectAccess.INSTALL_REQUIRED,
                    hasHistoryPermission = false,
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.onNodeWithTag("health_range_history").assertIsNotEnabled()
        composeRule.onAllNodesWithTag("request_history_permission").assertCountEquals(0)
        composeRule.onNodeWithTag("export_sleep_records").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun incompleteProviderSetupOffersHealthConnectOnboarding() {
        var openRequested = false
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = emptyList(),
                    healthConnectAccess = HealthConnectAccess.SETUP_REQUIRED,
                    onOpenHealthConnect = { openRequested = true },
                )
            }
        }

        composeRule.onNodeWithText("Set up Health Connect").assertIsDisplayed()
        composeRule.onNodeWithTag("open_health_connect").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertTrue(openRequested) }
    }

    @Test
    fun healthConnectRangeCanBeChangedInSettings() {
        var selectedRange by mutableStateOf(HealthDataRange.DEFAULT_PERIOD)
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    healthDataRange = selectedRange,
                    onHealthDataRangeChange = { selectedRange = it },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health_range_history").performClick()

        composeRule.onNodeWithTag("health_range_history").assertIsSelected()
    }

    @Test
    fun historyPermissionCanBeRequestedFromSettings() {
        var requested = false
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    hasHistoryPermission = false,
                    onRequestHistoryPermission = { requested = true },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("request_history_permission").performClick()

        composeRule.runOnIdle {
            assertTrue(requested)
        }
    }

    @Test
    fun sleepFileImportIsHiddenBehindAndroidVersionSupportState() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    fileWriteSupported = false,
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sleep_file_import_unsupported").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("export_sleep_records").assertIsDisplayed()
        composeRule.onAllNodesWithTag("import_sleep_files").assertCountEquals(0)
        composeRule.onAllNodesWithTag("delete_imported_records").assertCountEquals(0)
    }

    @Test
    fun sleepExportSelectsAllAvailablePackages() {
        var exportedPackages: Set<String> = emptySet()
        val range = SleepExportRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-15"),
            ZoneId.of("UTC"),
        )
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    fileWriteSupported = false,
                    exportPreparation = SleepExportPreparation(
                        range = range,
                        packages = listOf(
                            SleepExportPackage("example.one", "Example One", 4),
                            SleepExportPackage("example.two", "Example Two", 3),
                        ),
                    ),
                    onPrepareSleepExport = {},
                    onCreateSleepExportDocument = { exportedPackages = it },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_sleep_records").performScrollTo().performClick()
        composeRule.onNodeWithText("Example One").assertIsDisplayed()
        composeRule.onNodeWithText("Example Two").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_sleep_export").performClick()

        composeRule.runOnIdle {
            assertTrue(exportedPackages == setOf("example.one", "example.two"))
        }
    }

    @Test
    fun sleepFileImportUsesSingleSettingsCallback() {
        var imports = 0
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    fileWriteSupported = true,
                    onImportSleepFiles = { imports += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_sleep_files").performScrollTo().performClick()

        composeRule.runOnIdle { assertTrue(imports == 1) }
    }

    @Test
    fun legacyDebugImportIsShownWithoutDeletion() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    fileWriteSupported = true,
                    fileDeletionSupported = false,
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_sleep_files").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("legacy_debug_sleep_import_note").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("delete_imported_records").assertCountEquals(0)
    }

    @Test
    fun ownedSleepDeletionRequiresConfirmation() {
        var deletions = 0
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    fileWriteSupported = true,
                    fileImportedRecordCount = 7,
                    onDeleteOwnedSleepRecords = { deletions += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("imported_sleep_record_count").performScrollTo()
            .assertTextEquals("Imported records in selected range: 7")
        composeRule.onNodeWithTag("delete_imported_records").performClick()
        composeRule.onNodeWithTag("cancel_delete_imported_records").performClick()
        composeRule.runOnIdle { assertTrue(deletions == 0) }

        composeRule.onNodeWithTag("delete_imported_records").performClick()
        composeRule.onNodeWithTag("confirm_delete_imported_records").performClick()
        composeRule.runOnIdle { assertTrue(deletions == 1) }
    }

    @Test
    fun statsCanSwitchBetweenSelectedPeriodAndAllData() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records.take(8),
                    statsAllRecords = DemoData.records,
                    healthDataRange = HealthDataRange.custom(90),
                )
            }
        }

        composeRule.onNodeWithTag("destination_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stats_scope_selected").assertIsSelected()
        composeRule.onNodeWithText("Health Connect · Last 90 days", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("stats_scope_all").performClick()

        composeRule.onNodeWithTag("stats_scope_all").assertIsSelected()
        composeRule.onNodeWithText("Health Connect · All available data", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("tau_year_chart").assertIsDisplayed()
    }

    @Test
    fun statsScopeToggleIsHiddenWhenAllHistoryIsSelected() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    statsAllRecords = DemoData.records,
                    healthDataRange = HealthDataRange.ENTIRE_HISTORY,
                )
            }
        }

        composeRule.onNodeWithTag("destination_stats").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("stats_scope_selected").assertCountEquals(0)
        composeRule.onAllNodesWithTag("stats_scope_all").assertCountEquals(0)
        composeRule.onNodeWithText("Health Connect · All history", substring = true).assertIsDisplayed()
    }

    @Test
    fun statsAllDataUsesCacheWhenSwitchingBack() {
        var allDataRequests = 0
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records.take(8),
                    statsAllRecords = DemoData.records,
                    healthDataRange = HealthDataRange.custom(90),
                    onRequestStatsAllData = { allDataRequests++ },
                )
            }
        }

        composeRule.onNodeWithTag("destination_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stats_scope_all").performClick()
        composeRule.onNodeWithTag("stats_scope_selected").performClick()
        composeRule.onNodeWithTag("stats_scope_all").performClick()

        composeRule.runOnIdle {
            assertTrue(allDataRequests == 0)
        }
    }

    @Test
    fun statsAllDataRequestsHistoryPermissionWithoutChangingRange() {
        var requested = false
        var selectedRange by mutableStateOf(HealthDataRange.DEFAULT_PERIOD)
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    statsAllRecords = null,
                    healthDataRange = selectedRange,
                    hasHistoryPermission = false,
                    onRequestHistoryPermission = { requested = true },
                    onHealthDataRangeChange = { selectedRange = it },
                )
            }
        }

        composeRule.onNodeWithTag("destination_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stats_scope_all").performClick()

        composeRule.runOnIdle {
            assertTrue(requested)
            assertTrue(selectedRange == HealthDataRange.DEFAULT_PERIOD)
        }
    }

    @Test
    fun historyPermissionCalloutSwitchesToAllHistoryFromActogram() {
        var selectedRange by mutableStateOf(HealthDataRange.DEFAULT_PERIOD)
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    healthDataRange = selectedRange,
                    hasHistoryPermission = false,
                    onHealthDataRangeChange = { selectedRange = it },
                )
            }
        }

        composeRule.onNodeWithTag("actogram_history_callout").assertIsDisplayed()
        composeRule.onNodeWithText("Showing last 30 days").assertIsDisplayed()
        composeRule.onNodeWithTag("actogram_request_history_permission").performClick()

        composeRule.runOnIdle {
            assertTrue(selectedRange == HealthDataRange.ENTIRE_HISTORY)
        }
    }

    @Test
    fun historyPermissionCalloutRequestsPermissionWhenAllHistoryAlreadySelected() {
        var requested = false
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    healthDataRange = HealthDataRange.ENTIRE_HISTORY,
                    hasHistoryPermission = false,
                    onRequestHistoryPermission = { requested = true },
                )
            }
        }

        composeRule.onNodeWithTag("actogram_request_history_permission").performClick()

        composeRule.runOnIdle {
            assertTrue(requested)
        }
    }

    @Test
    fun historyPermissionCalloutCanBeDismissed() {
        var settings by mutableStateOf(AppSettings())
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(
                    records = DemoData.records,
                    initialSettings = settings,
                    onAppSettingsChange = { settings = it },
                    hasHistoryPermission = false,
                )
            }
        }

        composeRule.onNodeWithTag("actogram_history_callout").assertIsDisplayed()
        composeRule.onNodeWithTag("dismiss_history_callout").performClick()

        composeRule.onAllNodesWithTag("actogram_history_callout").assertCountEquals(0)
        composeRule.runOnIdle {
            assertTrue(settings.historyAccessCalloutDismissed)
        }
    }

    private fun setContent() {
        composeRule.setContent {
            DarkHourTheme {
                DarkHourApp(records = DemoData.records)
            }
        }
    }
}
