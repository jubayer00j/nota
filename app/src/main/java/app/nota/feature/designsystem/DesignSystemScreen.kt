package app.nota.feature.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nota.ui.components.NotaCard
import app.nota.ui.components.NotaChip
import app.nota.ui.components.NotaPrimaryButton
import app.nota.ui.components.NotaSecondaryButton
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaEditorBody
import app.nota.ui.theme.NotaSpacing

/**
 * Design-system preview (spec §12, Phase 0): the pine & paper tokens, the type
 * scale, and the component set on one scrollable sheet. If it looks wrong here,
 * it would look wrong everywhere — this screen is the design's unit test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignSystemScreen(onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Design system") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(NotaSpacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.lg),
        ) {
            SectionTitle("Colour roles (current scheme)")
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                Swatch("primary", MaterialTheme.colorScheme.primary)
                Swatch("secondary", MaterialTheme.colorScheme.secondary)
                Swatch("tertiary", MaterialTheme.colorScheme.tertiary)
                Swatch("surface", MaterialTheme.colorScheme.surface)
                Swatch("bg", MaterialTheme.colorScheme.background)
            }
            SectionTitle("Type scale")
            Text("Headline — Newsreader", style = MaterialTheme.typography.headlineMedium)
            Text("Title — Newsreader semibold", style = MaterialTheme.typography.titleLarge)
            Text(
                "Body — Public Sans. The quick brown fox jumps over the lazy dog. " +
                    "Pack my box with five dozen liquor jugs.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Editor body — Newsreader at reading line-height. লেখা পড়ার জন্য এই ছন্দ।",
                style = NotaEditorBody,
            )
            Text(
                "বাংলা — Hind Siliguri বান্ডল করা, তাই প্রতিটি ডিভাইসে একই দেখাবে।",
                style = MaterialTheme.typography.bodyLarge,
            )
            SectionTitle("Components")
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                NotaPrimaryButton("Primary", onClick = {})
                NotaSecondaryButton("Secondary", onClick = {})
                NotaChip("chip")
            }
            NoteCard(
                title = "A note card",
                snippet = "First attachment becomes the cover (§12).\n• Structured snippets keep list markers",
                updatedLabel = "Just now",
                folderName = "Design",
                tagNames = listOf("phase-0"),
                pinned = true,
                favourite = true,
            )
            NotaCard {
                Text("NotaCard container", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    "Spacing tokens: xs 4 · sm 8 · md 16 · lg 24 · xl 32 — the 8dp grid.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Swatch(label: String, color: Color) {
    Column {
        Box(
            Modifier.size(56.dp).background(color, RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(NotaSpacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
