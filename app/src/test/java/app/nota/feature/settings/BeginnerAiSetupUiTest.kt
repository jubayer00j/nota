package app.nota.feature.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.nota.feature.editor.AiNoticeDialog
import app.nota.feature.search.SearchScreen
import app.nota.ui.theme.NotaTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Beginner AI Setup #1 at the composition level — same harness
 * as SettingsScreenPolishUiTest: the REAL screens over the app's
 * own Koin graph, in guest posture (no key saved anywhere):
 *
 *  - the AI sub-screen's intro explains what a key is (item 9),
 *    the picker shows Gemini's plain description (item 2),
 *    Gemini's get-a-key steps + button render under the key
 *    field (item 1), and the model fields sit behind the
 *    collapsed "Advanced — models" fold whose header still names
 *    the effective models — opening it reveals both fields and
 *    their helper copy (item 3);
 *  - the editor's notice dialog offers "Open AI settings" only
 *    when the caller wires the action, and fires it (item 4);
 *  - Search shows its one quiet no-key hint line and taps
 *    through to the navigation callback (item 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BeginnerAiSetupUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // Same WorkManager guard as the settings polish suite:
        // Robolectric never installs the manifest provider.
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aiSubScreen_guidedSetup_foldHidesModelsUntilOpened() {
        composeRule.setContent {
            NotaTheme {
                SettingsAiScreen(onBack = {})
            }
        }
        awaitTrue("provider form loaded") { exists("Get a key") }

        // Item 9: the intro explains the key before leaning on it.
        composeRule.onNodeWithText("personal pass", substring = true).assertExists()
        // Item 2: the active provider's one-line description.
        composeRule.onNodeWithText("Google's AI — the suggested place to start.")
            .assertExists()
        // Item 1: Gemini's steps and the key-page button.
        composeRule.onNodeWithText("Choose “Create API key”.", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Get a key").assertExists()
        // Item 3: the fold starts collapsed — its header names the
        // effective models, the fields themselves are absent.
        composeRule.onNodeWithText("Advanced — models").assertExists()
        composeRule.onNodeWithText("gemini-3.5-flash-lite · gemini-embedding-001")
            .assertExists()
        composeRule.onAllNodesWithText("Embedding model").assertCountEquals(0)

        // The fold header sits below the visible fold of the
        // scrolling sub-screen — scroll it into view first, or
        // the click lands outside the window and never toggles.
        val foldHeader = composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Advanced — models")),
            useUnmergedTree = true,
        )
        foldHeader.performScrollTo()
        foldHeader.performClick()
        awaitTrue("model fields composed after opening the fold") {
            exists("Embedding model")
        }
        composeRule.onNodeWithText("Model").assertExists()
        // Item 3's helper copy, including the F4 embedding line.
        composeRule.onNodeWithText("The pre-filled model already works", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Powers search-by-meaning and Ask Nota", substring = true)
            .assertExists()
    }

    @Test
    fun editorNotice_settingsAction_whenWired_firesCallback() {
        var opened = false
        composeRule.setContent {
            NotaTheme {
                AiNoticeDialog(
                    message = "Add your API key in Settings → AI features to use AI.",
                    onDismiss = {},
                    onOpenSettings = { opened = true },
                )
            }
        }
        composeRule.onNodeWithText("Open AI settings").assertExists()
        composeRule.onNodeWithText("Open AI settings").performClick()
        composeRule.waitForIdle()
        assertTrue("the settings action must fire its callback", opened)
    }

    @Test
    fun editorNotice_plainError_hasNoSettingsAction() {
        // An ordinary provider error: OK only, no way out offered.
        composeRule.setContent {
            NotaTheme {
                AiNoticeDialog(
                    message = "You're offline — AI needs a connection.",
                    onDismiss = {},
                )
            }
        }
        composeRule.onAllNodesWithText("Open AI settings").assertCountEquals(0)
        composeRule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun search_showsNoKeyHint_andTapsThrough() {
        var opened = false
        composeRule.setContent {
            NotaTheme {
                SearchScreen(onOpenAiSettings = { opened = true })
            }
        }
        awaitTrue("no-key hint composed in guest posture") {
            exists("Search by meaning is off — add an API key to switch it on")
        }
        composeRule
            .onNodeWithText("Search by meaning is off — add an API key to switch it on")
            .performClick()
        composeRule.waitForIdle()
        assertTrue("the hint must fire its navigation callback", opened)
    }
}
