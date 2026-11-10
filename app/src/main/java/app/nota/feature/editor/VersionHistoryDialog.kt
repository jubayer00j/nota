package app.nota.feature.editor

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteVersionEntity
import app.nota.data.html.NoteDocument
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TextSegment
import app.nota.ui.theme.NotaSpacing
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Version history (Phase 6, PR #43) — Noma 1.x's
 * version-history-dialog translated to Nota's pine & paper:
 * list → read-only preview → restore, with restore itself
 * undoable: the current text is kept as the newest version
 * before the older one is applied. History lives on this
 * device only — stated in the footer, per the 1.x scope.
 *
 * Host orchestration (1.x): the screen flushes any pending
 * editor save BEFORE opening this dialog, so the state a
 * restore force-snapshots is the user's actual current text;
 * [onRestored] hands the restored row back so the screen can
 * drop its working buffer and re-seed from it.
 */
@Composable
fun VersionHistoryDialog(
    viewModel: RichNoteEditorViewModel,
    noteId: String,
    noteTitle: String,
    bodyStyle: TextStyle,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> File,
    onRestored: (NoteEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val versions by viewModel.observeVersions(noteId)
        .collectAsState(initial = null)
    var selected by remember { mutableStateOf<NoteVersionEntity?>(null) }
    var confirmingRestore by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun handleRestore() {
        val version = selected ?: return
        busy = true
        viewModel.restoreVersion(version.id) { restored ->
            busy = false
            if (restored != null) {
                Toast.makeText(context, "Version restored", Toast.LENGTH_SHORT).show()
                onRestored(restored)
                onDismiss()
            } else {
                Toast.makeText(
                    context,
                    "That version couldn't be restored.",
                    Toast.LENGTH_SHORT,
                ).show()
                confirmingRestore = false
            }
        }
    }

    fun handleClear() {
        busy = true
        viewModel.clearVersions(noteId)
        busy = false
        selected = null
        confirmingClear = false
        Toast.makeText(context, "Version history cleared", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Version history") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val trimmedTitle = noteTitle.trim()
                Text(
                    if (trimmedTitle.isEmpty()) {
                        "For note: “Untitled”"
                    } else {
                        "For note: “$trimmedTitle”"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                val current = selected
                if (current != null) {
                    VersionPreview(
                        version = current,
                        bodyStyle = bodyStyle,
                        attachments = attachments,
                        fileFor = fileFor,
                        confirmingRestore = confirmingRestore,
                        busy = busy,
                        onBack = {
                            selected = null
                            confirmingRestore = false
                        },
                        onAskRestore = { confirmingRestore = true },
                        onCancelRestore = { confirmingRestore = false },
                        onRestore = { handleRestore() },
                    )
                } else {
                    VersionList(
                        versions = versions,
                        onSelect = { selected = it },
                    )
                }
            }
        },
        confirmButton = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "History is kept on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected == null && !versions.isNullOrEmpty()) {
                    if (confirmingClear) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Clear all versions?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(NotaSpacing.sm))
                            TextButton(
                                onClick = { handleClear() },
                                enabled = !busy,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Clear") }
                            TextButton(
                                onClick = { confirmingClear = false },
                                enabled = !busy,
                            ) { Text("Cancel") }
                        }
                    } else {
                        TextButton(
                            onClick = { confirmingClear = true },
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Clear history") }
                    }
                }
            }
        },
    )
}

/** The list state: one row per version, newest first (1.x). */
@Composable
private fun VersionList(
    versions: List<NoteVersionEntity>?,
    onSelect: (NoteVersionEntity) -> Unit,
) {
    when {
        versions == null -> Text(
            "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = NotaSpacing.lg),
        )
        versions.isEmpty() -> Text(
            "No earlier versions yet — versions appear as you edit, " +
                "once a draft has had a moment to settle.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = NotaSpacing.lg),
        )
        else -> Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            versions.forEachIndexed { index, version ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(version) }
                        .padding(vertical = NotaSpacing.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            version.title.trim().ifEmpty { "Untitled" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatVersionAbsolute(version.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(NotaSpacing.sm))
                    Text(
                        formatVersionRelative(version.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The preview state: the version's title + content rendered
 * READ-ONLY through the editor's own read renderer (the same
 * [SegmentReadView] the read mode uses), then the restore
 * affordance with its inline confirm (1.x).
 */
@Composable
private fun VersionPreview(
    version: NoteVersionEntity,
    bodyStyle: TextStyle,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> File,
    confirmingRestore: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onAskRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onRestore: () -> Unit,
) {
    val previewSegments = remember(version.id) {
        NoteDocument.ensureTrailingTextSegment(
            NoteDocument.parse(version.contentHtml),
        ).map { UiSegment(newUiId(), it) }
    }
    // The screen's richStateFor pattern, scoped to this preview:
    // one library state per text/quote segment, seeded from the
    // stored HTML. Rendering only — toggles and edits go nowhere
    // (onChange below is a no-op by design).
    val previewStates = remember(version.id) { mutableMapOf<String, RichTextState>() }
    fun previewStateFor(ui: UiSegment): RichTextState {
        previewStates[ui.id]?.let { return it }
        val state = RichTextState()
        when (val s = ui.segment) {
            is TextSegment -> state.setHtml(s.html)
            is QuoteSegment -> state.setHtml(s.innerHtml)
            else -> Unit
        }
        previewStates[ui.id] = state
        return state
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !busy) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
                Spacer(Modifier.width(NotaSpacing.xs))
                Text("All versions")
            }
            Text(
                "${formatVersionAbsolute(version.createdAt)} · " +
                    formatVersionRelative(version.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(NotaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                Text(
                    version.title.trim().ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = app.nota.ui.theme.NotaSerifFamily,
                    ),
                )
                for (ui in previewSegments) {
                    SegmentReadView(
                        ui = ui,
                        stateFor = { previewStateFor(ui) },
                        onChange = {},
                        attachments = attachments,
                        fileFor = fileFor,
                        bodyStyle = bodyStyle,
                    )
                }
            }
        }
        Spacer(Modifier.height(NotaSpacing.md))
        if (confirmingRestore) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(NotaSpacing.md)) {
                    Text(
                        "Replace the current text with this version?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(NotaSpacing.xs))
                    Text(
                        "Your current text will be kept as the newest " +
                            "version, so this can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Row {
                        Button(onClick = onRestore, enabled = !busy) {
                            Text(if (busy) "Restoring…" else "Restore")
                        }
                        Spacer(Modifier.width(NotaSpacing.sm))
                        TextButton(onClick = onCancelRestore, enabled = !busy) {
                            Text("Cancel")
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onAskRestore, enabled = !busy) {
                    Text("Restore this version")
                }
            }
        }
    }
}

/** "10 Nov 2026, 2:47 pm" — the absolute stamp on each version row (1.x). */
internal fun formatVersionAbsolute(timestamp: Long): String {
    val formatted = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
        .format(Date(timestamp))
    // SimpleDateFormat renders "AM"/"PM"; 1.x shows the lowercase
    // form ("4:12 pm").
    return formatted.replace(Regex(" (AM|PM)$")) {
        " " + it.groupValues[1].lowercase(Locale.US)
    }
}

/** Compact relative stamp ("just now", "5 minutes ago", "3 days ago") — 1.x's ladder. */
internal fun formatVersionRelative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = maxOf(0L, (now - timestamp) / 1000)
    if (seconds < 60) return "just now"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"} ago"
    val hours = minutes / 60
    if (hours < 24) return "$hours hour${if (hours == 1L) "" else "s"} ago"
    val days = hours / 24
    if (days < 30) return "$days day${if (days == 1L) "" else "s"} ago"
    val months = days / 30
    if (months < 12) return "$months month${if (months == 1L) "" else "s"} ago"
    val years = months / 12
    return "$years year${if (years == 1L) "" else "s"} ago"
}
