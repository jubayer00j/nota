package app.nota.feature.ask

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

/** Ask Nota placeholder — grounded Q&A with citations arrives in Phase 4 (spec §7). */
@Composable
fun AskScreen() {
    Box(Modifier.fillMaxSize().padding(NotaSpacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ask Nota", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                "Ask questions across your notes and get answers with tappable citations. Phase 4.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
