package app.nota.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.preferences.SwipeAction
import app.nota.ui.theme.NotaSpacing
import app.nota.ui.theme.notaEditorBody
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The seven Settings sub-screens (Phase 6 presentation redesign,
 * mock-up approved by Jubayer 2026-11-08). Each host pairs the
 * shared scaffold (back + mini icon tile + serif title + a
 * plain-language intro sentence) with the category's EXISTING
 * section composables, re-housed into cards. Nothing here owns
 * behaviour: every section keeps its own state, callbacks,
 * dialogs and logic exactly as the flat layout had them — this
 * file only decides which card they sit in.
 */

// The intros: one plain sentence per category, before any
// control — what the screen is FOR, stated honestly.
private const val INTRO_ACCOUNT =
    "Who this library belongs to. Signed in, Nota opens your account's " +
        "own library; as a guest, your notes stay in a separate library " +
        "on this device."
private const val INTRO_SYNC =
    "Backups keep your notes safe if this phone is lost, replaced or " +
        "reset. Drive backups and local backup files share the Noma 1.x " +
        "format, so they move both ways."
private const val INTRO_SECURITY =
    "App Lock guards Nota on this device. Your backup passphrase is " +
        "different — it encrypts the backup file itself, so it travels " +
        "with your notes."
private const val INTRO_APPEARANCE =
    "How Nota looks on this device. These choices only change the " +
        "display — never your notes."
private const val INTRO_NOTES =
    "How your notes read, and how the list behaves: the text size " +
        "and line spacing for reading and writing, where Nota " +
        "starts, what a swipe does, and the templates you can " +
        "start a note from."
private const val INTRO_AI =
    // Beginner AI Setup #1: the key is explained BEFORE the word
    // is leaned on — a personal pass from the provider, created
    // on their site. No price promises; the clause stays honest
    // for free and paid providers alike.
    "Optional, and off until you add a key — your personal pass " +
        "from an AI provider, created on their site and pasted in " +
        "here. Everything in here runs on the provider key you bring."
private const val INTRO_ABOUT =
    "Which build of Nota is running on this device."

@Composable
fun SettingsAccountScreen(
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
) {
    SettingsSubScreen(SettingsCategory.ACCOUNT, INTRO_ACCOUNT, onBack) {
        // The account block is one logical unit; it gets a card of
        // its own without an extra title (its content leads with
        // the identity itself).
        SettingsCard {
            AccountSection(onOpenSignIn = onOpenSignIn)
        }
    }
}

@Composable
fun SettingsSyncScreen(onBack: () -> Unit) {
    SettingsSubScreen(SettingsCategory.SYNC, INTRO_SYNC, onBack) {
        // BackupSection renders the Drive cards + the local-file
        // card itself (see BackupSection / DriveBackupSection).
        BackupSection()
    }
}

@Composable
fun SettingsSecurityScreen(onBack: () -> Unit) {
    SettingsSubScreen(SettingsCategory.SECURITY, INTRO_SECURITY, onBack) {
        SettingsCard(title = "App Lock") {
            SecuritySection()
        }
        SettingsCard(title = "Backup encryption") {
            BackupEncryptionBlock()
        }
    }
}

@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    preferencesRepository: PreferencesRepository = koinInject(),
) {
    val dynamicColour by preferencesRepository.dynamicColour.collectAsState(initial = false)
    val oledBlack by preferencesRepository.oledBlack.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    SettingsSubScreen(SettingsCategory.APPEARANCE, INTRO_APPEARANCE, onBack) {
        SettingsCard {
            val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SettingsSwitchRow(
                title = "Material You dynamic colour",
                subtitle = if (dynamicSupported) {
                    "Match the app colours to your wallpaper (Android 12+)"
                } else {
                    "Needs Android 12 or newer — this device keeps the Nota theme"
                },
                checked = dynamicColour,
                enabled = dynamicSupported,
                onCheckedChange = { value ->
                    scope.launch { preferencesRepository.setDynamicColour(value) }
                },
            )
            SettingsSwitchRow(
                title = "OLED true black",
                subtitle = "Use pure black surfaces in dark mode to save battery on OLED screens",
                checked = oledBlack,
                onCheckedChange = { value ->
                    scope.launch { preferencesRepository.setOledBlack(value) }
                },
            )
        }
    }
}

@Composable
fun SettingsNotesScreen(
    onBack: () -> Unit,
    onOpenTemplates: () -> Unit,
    preferencesRepository: PreferencesRepository = koinInject(),
) {
    val startOnToday by preferencesRepository.startOnToday.collectAsState(initial = false)
    val swipeRight by preferencesRepository.swipeRightAction
        .collectAsState(initial = SwipeAction.ARCHIVE)
    val swipeLeft by preferencesRepository.swipeLeftAction
        .collectAsState(initial = SwipeAction.TRASH)
    // Phase 6 (PR #43): the Writing pair (1.x parity).
    val writingFontSize by preferencesRepository.writingFontSize
        .collectAsState(initial = PreferencesRepository.WRITING_FONT_SIZE_DEFAULT)
    val writingLineHeight by preferencesRepository.writingLineHeight
        .collectAsState(initial = PreferencesRepository.WRITING_LINE_HEIGHT_DEFAULT)
    val scope = rememberCoroutineScope()

    SettingsSubScreen(SettingsCategory.NOTES, INTRO_NOTES, onBack) {
        // Writing (1.x's Writing settings, phone-adapted): two
        // sliders + a live sample in the chosen type. 1.x's third
        // slider (editor width) is a desktop column concept with
        // no phone meaning and is deliberately not ported.
        SettingsCard(title = "Writing") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Font size", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${writingFontSize}px",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = writingFontSize.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        preferencesRepository.setWritingFontSize(value.roundToInt())
                    }
                },
                valueRange = PreferencesRepository.WRITING_FONT_SIZE_MIN.toFloat()..
                    PreferencesRepository.WRITING_FONT_SIZE_MAX.toFloat(),
                steps = PreferencesRepository.WRITING_FONT_SIZE_MAX -
                    PreferencesRepository.WRITING_FONT_SIZE_MIN - 1,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Line height", style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatLineHeight(writingLineHeight),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = writingLineHeight,
                onValueChange = { value ->
                    scope.launch { preferencesRepository.setWritingLineHeight(value) }
                },
                valueRange = PreferencesRepository.WRITING_LINE_HEIGHT_MIN..
                    PreferencesRepository.WRITING_LINE_HEIGHT_MAX,
                steps = 7,
            )
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                "Reading should feel calm — pick the size and " +
                    "spacing that suit your eyes, and every note " +
                    "follows.",
                style = notaEditorBody(writingFontSize, writingLineHeight)
                    .copy(color = MaterialTheme.colorScheme.onSurface),
            )
        }
        SettingsCard {
            SettingsSwitchRow(
                title = "Start on Today",
                subtitle = "Open the Today page at launch",
                checked = startOnToday,
                onCheckedChange = { value ->
                    scope.launch { preferencesRepository.setStartOnToday(value) }
                },
            )
            SwipeActionRow(
                title = "Swipe right",
                action = swipeRight,
                onSelect = { scope.launch { preferencesRepository.setSwipeRightAction(it) } },
            )
            SwipeActionRow(
                title = "Swipe left",
                action = swipeLeft,
                onSelect = { scope.launch { preferencesRepository.setSwipeLeftAction(it) } },
            )
            Text(
                "Swipes in the notes list. Delete archives the note and shows " +
                    "Undo — the note is only removed for good when you empty " +
                    "the trash.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Phase 3.4 (D2): the templates surface, previously
            // reachable only via the editor's "New from template".
            SettingsNavRow(
                title = "Templates",
                subtitle = "Start notes from templates; create them from any note's menu",
                onClick = onOpenTemplates,
            )
        }
    }
}

@Composable
fun SettingsAiScreen(onBack: () -> Unit) {
    SettingsSubScreen(SettingsCategory.AI, INTRO_AI, onBack) {
        // AiSection renders its own two cards (provider & key,
        // semantic search) — see AiSection.
        AiSection()
    }
}

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    appVersionName: String? = null,
) {
    val context = LocalContext.current
    val versionName = appVersionName ?: remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    SettingsSubScreen(SettingsCategory.ABOUT, INTRO_ABOUT, onBack) {
        SettingsCard {
            // Phase 6 polish: the installed version, read at
            // runtime from the PackageManager — never hard-coded,
            // so this row can never drift from the build it
            // describes. (Before this, the version appeared
            // nowhere in the running app's UI.)
            SettingsInfoRow("Version", versionName ?: "Unknown")
        }
    }
}
