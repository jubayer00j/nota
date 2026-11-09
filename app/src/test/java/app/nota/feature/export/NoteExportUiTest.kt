package app.nota.feature.export

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.nota.ui.theme.NotaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per-note Export #1 at the composition level: the export dialog
 * composed directly (the harness-proven route — the editor's
 * overflow-menu popup content never enters the semantics forest
 * under this Robolectric setup, as Editor Refinement #1
 * established, so the menu item itself is covered by the manual
 * device test instead). The dialog lists the three formats with
 * HTML as the default, the file-name preview follows the chosen
 * format (with the title sanitised), and Share / Save file fire
 * with the chosen format. The SAF picker and share sheet
 * themselves are platform surfaces and are exercised on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteExportUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogListsThreeFormatsWithHtmlDefaultAndFilenamePreview() {
        composeRule.setContent {
            NotaTheme {
                NoteExportDialog(
                    noteTitle = "Trip / plan",
                    onDismiss = {},
                    onShare = {},
                    onSave = {},
                )
            }
        }
        composeRule.onNodeWithText("Export note").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_FORMAT_HTML_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_FORMAT_MARKDOWN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_FORMAT_TEXT_TAG).assertIsDisplayed()
        // HTML is the default; the preview shows the sanitised name.
        composeRule.onNodeWithText("File: Trip plan.html").assertIsDisplayed()
        // Switching format updates the preview.
        composeRule.onNodeWithTag(EXPORT_FORMAT_MARKDOWN_TAG).performClick()
        composeRule.onNodeWithText("File: Trip plan.md").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_FORMAT_TEXT_TAG).performClick()
        composeRule.onNodeWithText("File: Trip plan.txt").assertIsDisplayed()
    }

    @Test
    fun shareAndSaveFireWithTheChosenFormat() {
        var shared: ExportFormat? = null
        var saved: ExportFormat? = null
        composeRule.setContent {
            NotaTheme {
                NoteExportDialog(
                    noteTitle = "Field notes",
                    onDismiss = {},
                    onShare = { shared = it },
                    onSave = { saved = it },
                )
            }
        }
        composeRule.onNodeWithTag(EXPORT_FORMAT_TEXT_TAG).performClick()
        composeRule.onNodeWithTag(EXPORT_SHARE_TAG).performClick()
        assertEquals(ExportFormat.TEXT, shared)
        composeRule.onNodeWithTag(EXPORT_SAVE_TAG).performClick()
        assertEquals(ExportFormat.TEXT, saved)
    }
}
