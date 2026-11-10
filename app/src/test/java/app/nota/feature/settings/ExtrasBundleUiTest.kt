package app.nota.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.nota.data.db.ReminderEntity
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.reminders.ReminderAlertType
import app.nota.feature.reminders.ReminderDialog
import app.nota.ui.theme.NotaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 6 extras (PR #45): the three new settings/dialog surfaces at
 * the composition level, over the app's own Koin graph (the
 * SettingsScreenPolishUiTest pattern):
 *
 *  - Appearance carries the Theme single-choice row; picking Dark
 *    persists the pinned mode (the host reads the same preference);
 *  - Notes & writing carries the Dictation language row; picking
 *    Bangla persists "bn";
 *  - the reminder dialog shows its tone controls ONLY for the
 *    Clock-alarm style — a notification reminder has no "Alarm
 *    sound" section and no Vibrate switch.
 *
 * The platform pickers (ringtone picker, document picker) cannot
 * run under Robolectric, so the dialog assertions pin visibility
 * and state only; the intent extras are pinned by
 * PhoneAlarmIntentsTest and the persistence by the repository
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtrasBundleUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val preferences: PreferencesRepository
        get() = GlobalContext.get().get()

    @Before
    fun setUp() {
        // Normalise the two PR #45 preferences on the app's real
        // store so test order inside this class cannot leak state.
        runBlocking {
            preferences.setThemeMode(PreferencesRepository.THEME_MODE_SYSTEM)
            preferences.setSpeechLanguage(PreferencesRepository.SPEECH_LANGUAGE_SYSTEM)
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

    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun appearance_themeRow_pinsDarkToThePreference() {
        composeRule.setContent {
            NotaTheme { SettingsAppearanceScreen(onBack = {}) }
        }
        awaitTrue("Theme row with System selected") {
            exists("Theme") && exists("System")
        }
        composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Theme")),
            useUnmergedTree = true,
        ).performClick()
        awaitTrue("Theme menu open") { exists("✓ System") || exists("Dark") }
        composeRule.onNodeWithText("Dark").performClick()
        awaitTrue("Dark persisted") {
            runBlocking { preferences.themeModeNow() } ==
                PreferencesRepository.THEME_MODE_DARK
        }
        awaitTrue("Row shows Dark") { exists("Dark") }
    }

    @Test
    fun notes_dictationLanguageRow_pinsBanglaToThePreference() {
        composeRule.setContent {
            NotaTheme {
                SettingsNotesScreen(onBack = {}, onOpenTemplates = {})
            }
        }
        awaitTrue("Dictation language row") { exists("Dictation language") }
        composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Dictation language")),
            useUnmergedTree = true,
        ).performClick()
        awaitTrue("Language menu open") { exists("Bangla") }
        composeRule.onNodeWithText("Bangla").performClick()
        awaitTrue("Bangla persisted") {
            runBlocking { preferences.speechLanguageNow() } ==
                PreferencesRepository.SPEECH_LANGUAGE_BANGLA
        }
        assertEquals(
            PreferencesRepository.SPEECH_LANGUAGE_BANGLA,
            runBlocking { preferences.speechLanguageNow() },
        )
    }

    private val futureTrigger: Long
        get() = System.currentTimeMillis() + 86_400_000L

    @Test
    fun reminderDialog_clockAlarmShowsToneControls() {
        val phoneAlarm = ReminderEntity(
            id = "r-alarm", noteId = "n1", triggerAt = futureTrigger,
            enabled = true, createdAt = 1_000L,
            alertType = ReminderAlertType.PHONE_ALARM,
        )
        composeRule.setContent {
            NotaTheme {
                ReminderDialog(
                    noteTitle = "Wake up",
                    existing = phoneAlarm,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onRemove = {},
                )
            }
        }
        awaitTrue("Tone section for a Clock alarm") {
            exists("Alarm sound") && exists("Choose tone…") && exists("Vibrate")
        }
        assertTrue(exists("System default"))
    }

    @Test
    fun reminderDialog_notificationHidesToneControls() {
        val notification = ReminderEntity(
            id = "r-note", noteId = "n1", triggerAt = futureTrigger,
            enabled = true, createdAt = 1_000L,
            alertType = ReminderAlertType.NOTIFICATION,
        )
        composeRule.setContent {
            NotaTheme {
                ReminderDialog(
                    noteTitle = "Wake up",
                    existing = notification,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onRemove = {},
                )
            }
        }
        awaitTrue("Dialog composed for a notification reminder") {
            exists("Notification")
        }
        assertTrue(!exists("Alarm sound"))
        assertTrue(!exists("Choose tone…"))
        assertTrue(!exists("Vibrate"))
    }
}
