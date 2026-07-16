package one.aozora.darkhour.ui.actogram

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import one.aozora.darkhour.data.SleepRecordDisplayMetadata
import one.aozora.darkhour.data.SleepRecordingMethod
import one.aozora.darkhour.ui.theme.DarkHourTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ActogramDetailsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactMetadataUsesHeaderAndKeepsShortNoteCollapsed() {
        composeRule.setContent {
            DarkHourTheme {
                ActogramDetailsPanel(
                    selection = sleepSelection(1L),
                    sleepMetadata = SleepRecordDisplayMetadata(
                        title = "Night sleep",
                        notes = "Felt rested",
                        sourceName = "Example Sleep",
                        sourceDevice = "Example Watch",
                        recordingMethod = SleepRecordingMethod.AUTOMATIC,
                    ),
                    useIsoDateTime = true,
                    onEditScheduleEntry = {},
                    onDismiss = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        composeRule.onNodeWithText("Night sleep").assertIsDisplayed()
        composeRule.onNodeWithText("Example Sleep · Example Watch · Automatic").assertIsDisplayed()
        composeRule.onNodeWithText("Notes: Felt rested").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Expand notes").assertCountEquals(0)
    }

    @Test
    fun longNoteExpandsCollapsesAndResetsForAnotherSelection() {
        var selectionId by mutableStateOf(1L)
        val longNote = List(30) { "Long sleep note" }.joinToString(" ")
        composeRule.setContent {
            DarkHourTheme {
                ActogramDetailsPanel(
                    selection = sleepSelection(selectionId),
                    sleepMetadata = SleepRecordDisplayMetadata(notes = longNote),
                    useIsoDateTime = true,
                    onEditScheduleEntry = {},
                    onDismiss = {},
                    modifier = Modifier.width(700.dp),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Expand notes").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_notes_toggle").performClick()
        composeRule.onNodeWithContentDescription("Collapse notes").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_notes_toggle").performClick()
        composeRule.onNodeWithContentDescription("Expand notes").assertIsDisplayed()

        composeRule.onNodeWithTag("sleep_notes_toggle").performClick()
        composeRule.runOnIdle { selectionId = 2L }
        composeRule.onNodeWithContentDescription("Expand notes").assertIsDisplayed()
    }

    private fun sleepSelection(logId: Long) = ActogramSelection.Sleep(
        logId = logId,
        startTime = Instant.parse("2026-07-15T21:00:00Z"),
        endTime = Instant.parse("2026-07-16T05:00:00Z"),
        startZoneOffset = ZoneOffset.ofHours(3),
        endZoneOffset = ZoneOffset.ofHours(3),
        sleepScore = 0.8,
        stages = null,
        isMainSleep = true,
    )
}
