package app.nota.feature.reminders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.nota.ui.theme.NotaSpacing

/** Reminders placeholder — alarms + notifications arrive in Phase 5 (spec §7). */
@Composable
fun RemindersScreen() {
    Box(Modifier.fillMaxSize().padding(NotaSpacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Reminders", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                "Note reminders with exact alarms, like Noma 1.x. Phase 5.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
