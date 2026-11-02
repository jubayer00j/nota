package app.nota.feature.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.nota.data.preferences.PreferencesRepository
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings placeholder. The 8 parity categories (Sync & backup, Security,
 * Appearance, Writing, AI features, Data, Account, About) fill in as their
 * features land (spec §6). Phase 0 links the two review surfaces from here;
 * Phase 1a adds the first functional category — Appearance.
 */
@Composable
fun SettingsScreen(
    onOpenDesignSystem: () -> Unit = {},
    onOpenEditorSpike: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
    preferencesRepository: PreferencesRepository = koinInject(),
) {
    val dynamicColour by preferencesRepository.dynamicColour.collectAsState(initial = false)
    val oledBlack by preferencesRepository.oledBlack.collectAsState(initial = false)
    val startOnToday by preferencesRepository.startOnToday.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    // Scrollable so the growing category list can never be clipped behind
    // the shell's bottom navigation bar (Phase 1a inset audit). The shell
    // already passes its innerPadding to the NavHost, so this column lives
    // entirely above the bottom bar.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NotaSpacing.md),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(NotaSpacing.md))

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
            title = "Start on Today",
            subtitle = "Applies on next launch",
            checked = startOnToday,
            onCheckedChange = { enabled ->
                scope.launch { preferencesRepository.setStartOnToday(enabled) }
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

        SecuritySection()
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        BackupSection(onOpenSignIn = onOpenSignIn)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        AccountSection(onOpenSignIn = onOpenSignIn)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        Text(
            "Writing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsRow("Templates", "Start notes from templates; create them from any note's menu", onOpenTemplates)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.sm))

        SettingsRow("Design system preview", "Pine & paper tokens, type scale, components", onOpenDesignSystem)
        HorizontalDivider()
        SettingsRow("Editor spike (Phase 0)", "Compose Rich Editor go/no-go test bench", onOpenEditorSpike)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.md))
        Text(
            "AI features · Data · About — arrive with their phases.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
