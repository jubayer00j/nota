package app.nota.feature.templates

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.nota.ui.theme.NotaSpacing
import org.koin.androidx.compose.koinViewModel

/**
 * Templates (Phase 1c): tap a template to start a note from it. New
 * templates are created from any note via the editor's "Save as template".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    viewModel: TemplatesViewModel = koinViewModel(),
) {
    val templates by viewModel.templates.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Templates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(NotaSpacing.md),
        ) {
            if (templates.isEmpty()) {
                Text(
                    "No templates yet. Open a note and choose \"Save as template\" " +
                        "from its menu to create one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            templates.forEach { template ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name, style = MaterialTheme.typography.bodyLarge)
                        if (template.builtIn) {
                            Text(
                                "Built-in",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.apply(template, onOpenNote) }) {
                        Text("Use")
                    }
                    if (!template.builtIn) {
                        IconButton(onClick = { viewModel.delete(template) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete template")
                        }
                    }
                }
            }
            Spacer(Modifier.height(NotaSpacing.md))
            Text(
                "Applying a template creates a new note with its content; the template itself is unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
