package app.nota.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nota.ui.theme.NotaSpacing

/**
 * The Phase 4.1 AI action surfaces for the editor. Both actions
 * are strictly confirm-before-apply (1.x v1 scope): a summary is
 * only inserted when the user taps Insert, tags only change when
 * the user taps Apply — the model's output never touches the note
 * on its own.
 */

/** In-flight indicator with a working Cancel (the service call is
 *  a coroutine Job; cancelling it cancels the HTTP request). */
@Composable
fun AiProgressDialog(label: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* only the Cancel button dismisses */ },
        title = { Text(label) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Spacer(Modifier.size(NotaSpacing.md))
                Text("Asking the provider…")
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

/** A one-message dialog: the no-key pointer to Settings, or a
 *  mapped provider error in its calm form. Beginner AI Setup #1
 *  (F6): when the failure is a setup gap (no key / provider not
 *  finished), the caller passes [onOpenSettings] and the dialog
 *  offers the way out — the same Settings → AI features
 *  destination Ask Nota's gates use — instead of leaving the
 *  user at a dead end. Other errors keep OK only. */
@Composable
fun AiNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = onOpenSettings?.let { openSettings ->
            {
                TextButton(onClick = openSettings) { Text("Open AI settings") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSummarySheet(
    summary: String,
    truncated: Boolean,
    onInsertAtTop: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = NotaSpacing.md)
                .padding(bottom = NotaSpacing.lg),
        ) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(summary, style = MaterialTheme.typography.bodyLarge)
            if (truncated) {
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    "The note was long, so its middle was omitted before summarizing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(NotaSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                OutlinedButton(onClick = onInsertAtTop) { Text("Insert at top") }
                OutlinedButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTagsDialog(
    tags: List<String>,
    truncated: Boolean,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(tags) { mutableStateOf(tags.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suggested tags") },
        text = {
            Column {
                if (tags.isEmpty()) {
                    Text("The provider didn't suggest any new tags for this note.")
                } else {
                    Text("Tap to choose which tags to add:")
                    Spacer(Modifier.height(NotaSpacing.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = tag in selected,
                                onClick = {
                                    selected = if (tag in selected) {
                                        selected - tag
                                    } else {
                                        selected + tag
                                    }
                                },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                if (truncated) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        "The note was long, so its middle was omitted before suggesting.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(tags.filter { it in selected }) },
                enabled = selected.isNotEmpty(),
            ) {
                Text("Apply ${selected.size} tag${if (selected.size == 1) "" else "s"}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Discard") }
        },
    )
}
