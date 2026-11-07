package app.nota.feature.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
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
 * Phase 6 polish: the Settings reorganisation, pinned at the
 * composition level. Composes the REAL [SettingsScreen] over the
 * app's own Koin graph — NotaApplication.onCreate starts it under
 * Robolectric exactly as in production (a second startKoin here
 * would throw KoinApplicationAlreadyStartedException) — in guest
 * posture (no Firebase under Robolectric; AuthManager fails safe
 * to Guest), and asserts:
 *
 *  - the seven sections exist in the approved order (Account →
 *    Sync & backup → Security & privacy → Appearance →
 *    Notes & writing → AI features → About);
 *  - the Phase 0 dev surfaces (design-system preview, editor spike)
 *    and the stale "arrive with their phases" footer are GONE;
 *  - the guest sees exactly ONE "Sign in" control (Account's) — the
 *    Drive block's duplicate was removed;
 *  - the About section shows a Version row carrying the version
 *    string the screen was given (production reads it from the
 *    PackageManager; the row must render what it is handed, never
 *    a hard-coded literal of its own);
 *  - "Backup encryption" now lives under Security & privacy (below
 *    that header, and no longer inside Sync & backup's span).
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
        composeRule.setContent {
            NotaTheme {
                SettingsScreen(
                    onOpenTemplates = {},
                    onOpenSignIn = {},
                    appVersionName = "0.13.0",
                )
            }
        }
    }

    /** Some sections appear only after async loads (AI settings);
     *  poll the main looper until [condition] holds or give up. */
    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun settingsSectionsInApprovedOrder_devSurfacesGone_singleSignIn() {
        awaitTrue("all seven section headers composed") {
            listOf(
                "Account", "Sync & backup", "Security & privacy", "Appearance",
                "Notes & writing", "AI features", "About",
            ).all { exists(it) }
        }

        val tops = listOf(
            "Account", "Sync & backup", "Security & privacy", "Appearance",
            "Notes & writing", "AI features", "About",
        ).map { topOf(it) }
        assertTrue(
            "section headers must be strictly ordered, got $tops",
            tops.zipWithNext().all { (a, b) -> a < b },
        )

        // The Phase 0 dev surfaces + stale footer are deleted.
        composeRule.onNodeWithText("Design system preview").assertDoesNotExist()
        composeRule.onNodeWithText("Editor spike (Phase 0)").assertDoesNotExist()
        composeRule.onNodeWithText("Data · About — arrive with their phases.")
            .assertDoesNotExist()

        // Exactly one Sign in control: Account's. (The Drive block's
        // guest prose mentions signing in as part of a longer
        // sentence — exact-text matching keeps that from counting.)
        composeRule.onAllNodesWithText("Sign in", substring = false)
            .assertCountEquals(1)

        // About: the version row renders the handed-in version.
        composeRule.onNodeWithText("Version").assertExists()
        composeRule.onNodeWithText("0.13.0").assertExists()

        // Backup encryption moved: its header sits below the
        // Security & privacy header (and below Sync & backup's).
        awaitTrue("Backup encryption header composed") { exists("Backup encryption") }
        assertTrue(topOf("Backup encryption") > topOf("Security & privacy"))
        assertTrue(topOf("Backup encryption") > topOf("Sync & backup"))
    }
}
