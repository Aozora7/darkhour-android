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
import androidx.compose.ui.test.assertIsSelected
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
import one.aozora.darkhour.ui.AppSettings
import one.aozora.darkhour.ui.theme.DarkHourTheme
import one.aozora.darkhour.data.HealthConnectAccess
import one.aozora.darkhour.data.HealthDataRange
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
