package app.nota.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import java.io.File
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    title: String,
    snippet: String,
    updatedLabel: String,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    tagNames: List<String> = emptyList(),
    /** Search Quality Pass: a pre-styled snippet (the search
     *  screen's term highlighting). When set, it replaces
     *  [snippet] as the card's body text. */
    snippetAnnotated: AnnotatedString? = null,
    /** Search Quality Pass: a small marker chip rendered ahead
     *  of the folder/tag chips (search flags meaning-only hits
     *  with it). */
    badgeText: String? = null,
    pinned: Boolean = false,
    favourite: Boolean = false,
    coverImagePath: String? = null,
    selected: Boolean = false,
    /** Phase 2b: a locked note's card shows title + lock affordance ONLY —
     *  no snippet and no cover, even if a caller passes them (the seal
     *  must hold at the component, not just at each call site). */
    locked: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = NotaSpacing.xs),
    ) {
        if (coverImagePath != null && !locked) {
            // Local files only (Phase 1b): absolute paths load as Files,
            // anything else is passed to Coil as-is. No network module.
            val model: Any = if (coverImagePath.startsWith("/")) File(coverImagePath) else coverImagePath
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.padding(NotaSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (locked) {
                Spacer(Modifier.height(NotaSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(NotaSpacing.xs))
                    Text(
                        text = "Locked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (snippetAnnotated != null) {
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    text = snippetAnnotated,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (snippet.isNotBlank()) {
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
            if (badgeText != null || folderName != null || tagNames.isNotEmpty()) {
                Spacer(Modifier.height(NotaSpacing.xs))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NotaSpacing.xs),
                    maxLines = 2,
                ) {
                    if (badgeText != null) NotaChip(badgeText)
                    if (folderName != null) NotaChip(folderName)
                    tagNames.forEach { NotaChip("#$it") }
                }
            }
        }
    }
}
