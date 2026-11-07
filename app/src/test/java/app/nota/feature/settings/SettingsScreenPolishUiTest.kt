package app.nota.feature.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.nota.ui.theme.NotaTheme
import org.junit.Assert.assertEquals
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
 * Phase 6 polish + presentation redesign: the Settings structure,
 * pinned at the composition level. Composes the REAL settings
 * screens over the app's own Koin graph — NotaApplication.onCreate
 * starts it under Robolectric exactly as in production (a second
 * startKoin here would throw
 * KoinApplicationAlreadyStartedException) — in guest posture (no
 * Firebase under Robolectric; AuthManager fails safe to Guest),
 * and asserts:
 *
 *  - the ROOT is a category list (not one long scroll): the seven
 *    categories in the approved order (Account → Sync & backup →
 *    Security & privacy → Appearance → Notes & writing →
 *    AI features → About), each with a LIVE summary line derived
 *    from the guest state ("Guest — notes stay on this device",
 *    "App lock Off · Encryption Off", "Version 0.13.0", …);
 *  - no section content is inlined on the root any more (no
 *    "Backup encryption", no "Sign in" control — that lives on
 *    the Account sub-screen now, still exactly once);
 *  - the Phase 0 dev surfaces (design-system preview, editor
 *    spike) and the stale "arrive with their phases" footer are
 *    still GONE;
 *  - tapping a category row reports that category to the
 *    navigation callback;
 *  - sub-screens carry the scaffold header (back affordance +
 *    category title), their plain-language intro sentence, and
 *    the re-housed cards (Security: "App Lock" + "Backup
 *    encryption"; Sync: "Google Drive" + "Local backup file");
 *  - the About sub-screen shows a Version row carrying the
 *    version string the screen was given (production reads it
 *    from the PackageManager; the row must render what it is
 *    handed, never a hard-coded literal of its own).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenPolishUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // The Drive section's scheduler effect calls
        // WorkManager.getInstance: production gets WorkManager from
        // its manifest ContentProvider, which Robolectric never
        // installs — initialise it manually (idempotent-guarded).
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    /** Some state arrives only after async loads (AI settings,
     *  Drive grant probes); poll the main looper until [condition]
     *  holds or give up. */
    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * Every text in the (unmerged) semantics tree, in traversal
     * order. The root composes its cards top to bottom, so
     * traversal order IS screen order — unlike boundsInRoot, which
     * reports 0 for the not-yet-laid-out rows below the scroll
     * fold under Robolectric.
     */
    private fun semanticsTextsInOrder(): List<String> {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            val texts = runCatching { node.config[SemanticsProperties.Text] }
                .getOrNull().orEmpty()
            texts.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private val categoryTitles = listOf(
        "Account", "Sync & backup", "Security & privacy", "Appearance",
        "Notes & writing", "AI features", "About",
    )

    @Test
    fun settingsRoot_categoriesInOrder_liveSummaries_noInlinedSections() {
        var clicked: SettingsCategory? = null
        composeRule.setContent {
            NotaTheme {
                SettingsScreen(
                    onOpenCategory = { clicked = it },
                    appVersionName = "0.13.0",
                )
            }
        }

        awaitTrue("all seven category titles composed") {
            categoryTitles.all { exists(it) }
        }
        awaitTrue("AI summary settled to its guest truth") { exists("Not set up") }

        val ordered = semanticsTextsInOrder()
        val positions = categoryTitles.map { ordered.indexOf(it) }
        assertTrue(
            "category titles must appear in order in the semantics tree, " +
                "got positions $positions in $ordered",
            positions.all { it >= 0 } &&
                positions.zipWithNext().all { (a, b) -> a < b },
        )

        // The live summaries, in guest posture — each derived from
        // the same state its sub-screen observes.
        composeRule.onNodeWithText("Guest — notes stay on this device").assertExists()
        composeRule.onNodeWithText("Sign in to use Drive backup").assertExists()
        composeRule.onNodeWithText("App lock Off · Encryption Off").assertExists()
        composeRule.onNodeWithText("Pine & paper · OLED off").assertExists()
        composeRule.onNodeWithText("Starts on Notes").assertExists()
        composeRule.onNodeWithText("Not set up").assertExists()
        composeRule.onNodeWithText("Version 0.13.0").assertExists()

        // The root inlines NO section content any more.
        composeRule.onNodeWithText("Backup encryption").assertDoesNotExist()
        composeRule.onAllNodesWithText("Sign in", substring = false)
            .assertCountEquals(0)

        // The Phase 0 dev surfaces + stale footer stay deleted.
        composeRule.onNodeWithText("Design system preview").assertDoesNotExist()
        composeRule.onNodeWithText("Editor spike (Phase 0)").assertDoesNotExist()
        composeRule.onNodeWithText("Data · About — arrive with their phases.")
            .assertDoesNotExist()

        // Tapping a category row reports that category.
        composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Security & privacy")),
        ).performClick()
        composeRule.waitForIdle()
        assertEquals(SettingsCategory.SECURITY, clicked)
    }

    @Test
    fun securitySubScreen_headerIntroAndCards() {
        composeRule.setContent {
            NotaTheme {
                SettingsSecurityScreen(onBack = {})
            }
        }
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Security & privacy").assertExists()
        // The approved intro sentence (fragment match — the intro
        // is one long text node).
        composeRule.onNodeWithText("encrypts the backup file itself", substring = true)
            .assertExists()
        // Two cards: the App Lock card title + the section's own
        // lead row both read "App Lock"; the encryption card title
        // stands alone (the block's own header is gone).
        composeRule.onAllNodesWithText("App Lock").assertCountEquals(2)
        composeRule.onNodeWithText("Backup encryption").assertExists()
        // App Lock is off in guest posture, so its Set up control shows.
        composeRule.onNodeWithText("Set up").assertExists()
    }

    @Test
    fun accountSubScreen_singleSignIn() {
        composeRule.setContent {
            NotaTheme {
                SettingsAccountScreen(onBack = {}, onOpenSignIn = {})
            }
        }
        composeRule.onNodeWithText("Account").assertExists()
        // Exactly one Sign in control: Account's. (Other copy on
        // this screen mentions signing in only inside longer
        // sentences — exact-text matching keeps those from
        // counting.)
        composeRule.onAllNodesWithText("Sign in", substring = false)
            .assertCountEquals(1)
    }

    @Test
    fun syncSubScreen_driveAndLocalCards() {
        composeRule.setContent {
            NotaTheme {
                SettingsSyncScreen(onBack = {})
            }
        }
        awaitTrue("Drive card composed") { exists("Google Drive") }
        composeRule.onNodeWithText("Google Drive").assertExists()
        composeRule.onNodeWithText("Local backup file").assertExists()
        // Guest posture: no Automatic backup card (it exists only
        // once Drive is connected).
        composeRule.onNodeWithText("Automatic backup").assertDoesNotExist()
    }

    @Test
    fun aboutSubScreen_showsRuntimeVersion() {
        composeRule.setContent {
            NotaTheme {
                SettingsAboutScreen(onBack = {}, appVersionName = "0.13.0")
            }
        }
        composeRule.onNodeWithText("About").assertExists()
        composeRule.onNodeWithText("Version").assertExists()
        composeRule.onNodeWithText("0.13.0").assertExists()
    }
}
