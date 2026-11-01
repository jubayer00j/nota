package app.nota.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.nota.ui.theme.NotaSpacing

/** Primary action in Nota's voice: filled pine button. */
@Composable
fun NotaPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) { Text(text) }
}

/** Quiet secondary action. */
@Composable
fun NotaSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}

/** Small metadata chip (folders, tags). */
@Composable
fun NotaChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    AssistChip(onClick = onClick ?: {}, label = { Text(text) }, modifier = modifier)
}

/** Generic raised paper card. */
@Composable
fun NotaCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = NotaSpacing.xs),
    ) {
        Column(Modifier.padding(NotaSpacing.md)) { content() }
    }
}

/**
 * The note card — the design system's most important component (spec §6/§12):
 * serif title, structured snippet, folder/tag chips, pin + favourite affordances.
 * Phase 0 renders it with preview data; Phase 1 binds it to Room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteCard(
    title: String,
    snippet: String,
    updatedLabel: String,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    tagNames: List<String> = emptyList(),
    pinned: Boolean = false,
    favourite: Boolean = false,
    onClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = NotaSpacing.xs),
    ) {
        Column(Modifier.padding(NotaSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (snippet.isNotBlank()) {
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(NotaSpacing.sm))
            // Timestamp + favourite keep their own row; the chips below
            // them wrap inside the card (Phase 1a — a single Row let long
            // folder/tag lists overflow the card's edge on device).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                Text(
                    text = updatedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (favourite) {
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (folderName != null || tagNames.isNotEmpty()) {
                Spacer(Modifier.height(NotaSpacing.xs))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NotaSpacing.xs),
                    maxLines = 2,
                ) {
                    if (folderName != null) NotaChip(folderName)
                    tagNames.forEach { NotaChip("#$it") }
                }
            }
        }
    }
}
