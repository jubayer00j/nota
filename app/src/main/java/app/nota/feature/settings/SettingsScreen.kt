package app.nota.feature.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.preferences.SwipeAction
import app.nota.feature.notes.label
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings (Phase 6 polish reorganisation). One scroll, seven
 * sections in user-logic order — identity first, system last:
 *
 * 1. Account — who this library belongs to (sign in/out, guest move)
 * 2. Sync & backup — Drive + local-file backup sub-groups
 * 3. Security & privacy — App Lock + backup encryption
 * 4. Appearance — theme switches
 * 5. Notes & writing — launch behaviour, list swipes, templates
 * 6. AI features — BYOK provider + semantic search sub-group
 * 7. About — the installed version
 *
 * Header tiers are uniform: top-level sections are titleMedium in
 * the primary colour; sub-groups inside a section are titleSmall.
 * The Phase 0 review surfaces (design-system preview, editor
 * spike) that used to hang off the bottom of this screen are gone —
 * they were never user settings.
 *
 * [appVersionName] is injectable for tests; production reads the
 * REAL installed version from the PackageManager — never a
 * hard-coded string — so the About row can never drift from the
 * build it describes.
 */
@Composable
fun SettingsScreen(
    onOpenTemplates: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
    appVersionName: String? = null,
    preferencesRepository: PreferencesRepository = koinInject(),
) {
    val dynamicColour by preferencesRepository.dynamicColour.collectAsState(initial = false)
    val oledBlack by preferencesRepository.oledBlack.collectAsState(initial = false)
    val startOnToday by preferencesRepository.startOnToday.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val versionName = appVersionName ?: remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    // Scrollable so the category list can never be clipped behind
    // the shell's bottom navigation bar (Phase 1a inset audit). The
    // shell already passes its innerPadding to the NavHost, so this
    // column lives entirely above the bottom bar.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NotaSpacing.md),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(NotaSpacing.md))

        // 1 — Account (identity first: the Drive state below depends
        // on this sign-in, so it no longer sits underneath it).
        AccountSection(onOpenSignIn = onOpenSignIn)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 2 — Sync & backup (Drive + local file sub-groups).
        BackupSection()
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 3 — Security & privacy (App Lock + backup encryption).
        SecuritySection()
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 4 — Appearance.
        Text(
            "Appearance",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // Dynamic colour only exists on Android 12+ (API 31): NotaTheme
        // gates it on SDK_INT >= S, so below that the switch must not
        // pretend to work — disable it with an explanatory subtitle.
        // The persisted preference is left stored and takes effect if
        // the device is ever upgraded.
        val dynamicColourSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SettingsSwitchRow(
            title = "Material You dynamic colour",
            subtitle = if (dynamicColourSupported) {
                "Colour the app from your wallpaper instead of Nota's pine & paper theme"
            } else {
                "Requires Android 12 or newer — not available on this device"
            },
            checked = dynamicColour,
            enabled = dynamicColourSupported,
            onCheckedChange = { enabled ->
                scope.launch { preferencesRepository.setDynamicColour(enabled) }
            },
        )
        SettingsSwitchRow(
            title = "OLED true black",
            subtitle = "Use the true-black theme, saving power on OLED screens",
            checked = oledBlack,
            onCheckedChange = { enabled ->
                scope.launch { preferencesRepository.setOledBlack(enabled) }
            },
        )
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 5 — Notes & writing (Phase 3.5's "Notes list" + the old
        // one-row "Writing" section, merged; "Start on Today" moved
        // here from Appearance — it is launch behaviour, not theme).
        Text(
            "Notes & writing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsSwitchRow(
            title = "Start on Today",
            subtitle = "Applies on next launch",
            checked = startOnToday,
            onCheckedChange = { enabled ->
                scope.launch { preferencesRepository.setStartOnToday(enabled) }
            },
        )
        // Phase 3.5 (D3): per-direction swipe actions for the main
        // notes list. Persisted in preferences and applied by the
        // list immediately (the ViewModel exposes them as a flow).
        val swipeRight by preferencesRepository.swipeRightAction
            .collectAsState(initial = SwipeAction.ARCHIVE)
        val swipeLeft by preferencesRepository.swipeLeftAction
            .collectAsState(initial = SwipeAction.TRASH)
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
            "Applies to All notes, folders, tags and Favourites. " +
                "Archived and Trash keep their own actions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsNavRow(
            "Templates",
            "Start notes from templates; create them from any note's menu",
            onOpenTemplates,
        )
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 6 — AI features (BYOK provider setup + semantic search).
        AiSection()
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        // 7 — About.
        Text(
            "About",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsInfoRow("Version", versionName ?: "Unknown")
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Phase 3.5 (D3): a swipe-direction row; tapping opens the action menu. */
@Composable
private fun SwipeActionRow(
    title: String,
    action: SwipeAction,
    onSelect: (SwipeAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title = title, subtitle = action.label(), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SwipeAction.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text((if (option == action) "✓ " else "") + option.label()) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(vertical = NotaSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one navigation-row shape (Phase 6 polish): same text stack as
 * every other row, plus the chevron that says "this goes somewhere"
 * — the settings rows that open another screen were previously
 * indistinguishable from static labels.
 */
@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = NotaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A static information row (no tap action): the About version. */
@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Column(Modifier.padding(vertical = NotaSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
