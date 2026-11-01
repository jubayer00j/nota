package app.nota.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nota.ui.theme.NotaSpacing

/**
 * Settings placeholder. The 8 parity categories (Sync & backup, Security,
 * Appearance, Writing, AI features, Data, Account, About) fill in as their
 * features land (spec §6). Phase 0 links the two review surfaces from here.
 */
@Composable
fun SettingsScreen(
    onOpenDesignSystem: () -> Unit = {},
    onOpenEditorSpike: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(NotaSpacing.md)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(NotaSpacing.md))
        SettingsRow("Design system preview", "Pine & paper tokens, type scale, components", onOpenDesignSystem)
        HorizontalDivider()
        SettingsRow("Editor spike (Phase 0)", "Compose Rich Editor go/no-go test bench", onOpenEditorSpike)
        HorizontalDivider()
        Spacer(Modifier.height(NotaSpacing.md))
        Text(
            "Sync & backup · Security · Appearance · Writing · AI features · Data · Account · About — arrive with their phases.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
