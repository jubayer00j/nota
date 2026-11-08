package app.nota.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.nota.ui.theme.NotaSpacing
import java.io.File

/**
 * The per-note export dialog (Phase 6, Per-note Export #1) —
 * Noma 1.x's note-export-dialog flow translated to the editor's
 * pine & paper dialogs: pick a format (HTML by default), see the
 * file name the export will get, then deliver it — **Save file**
 * (system file picker) or **Share…** (the share sheet, with the
 * real file attached). Two taps from the menu to a delivered
 * file either way.
 */

const val EXPORT_FORMAT_HTML_TAG = "export-format-html"
const val EXPORT_FORMAT_MARKDOWN_TAG = "export-format-markdown"
const val EXPORT_FORMAT_TEXT_TAG = "export-format-text"
const val EXPORT_SHARE_TAG = "export-share"
const val EXPORT_SAVE_TAG = "export-save"

@Composable
fun NoteExportDialog(
    noteTitle: String,
    onDismiss: () -> Unit,
    onShare: (ExportFormat) -> Unit,
    onSave: (ExportFormat) -> Unit,
    busy: Boolean = false,
) {
    var format by remember { mutableStateOf(ExportFormat.HTML) }

    fun hint(forFormat: ExportFormat): String = when (forFormat) {
        ExportFormat.HTML -> "Looks like the note, images included. Opens in any browser."
        ExportFormat.MARKDOWN -> "For other notes apps and editors."
        ExportFormat.TEXT -> "Just the words. Works everywhere."
    }

    fun tag(forFormat: ExportFormat): String = when (forFormat) {
        ExportFormat.HTML -> EXPORT_FORMAT_HTML_TAG
        ExportFormat.MARKDOWN -> EXPORT_FORMAT_MARKDOWN_TAG
        ExportFormat.TEXT -> EXPORT_FORMAT_TEXT_TAG
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export note") },
        text = {
            Column {
                val trimmedTitle = noteTitle.trim()
                if (trimmedTitle.isNotEmpty()) {
                    Text(
                        trimmedTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                }
                for (option in ExportFormat.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { format = option }
                            .padding(vertical = NotaSpacing.xs)
                            .testTag(tag(option)),
                    ) {
                        RadioButton(
                            selected = format == option,
                            onClick = { format = option },
                            enabled = !busy,
                        )
                        Column(Modifier.padding(start = NotaSpacing.sm)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                hint(option),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(NotaSpacing.md))
                Text(
                    "File: ${NoteExport.exportFilename(noteTitle, format)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Text(
                            "Preparing export…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = NotaSpacing.sm),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { onShare(format) },
                modifier = Modifier.testTag(EXPORT_SHARE_TAG),
            ) { Text("Share…") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = { onSave(format) },
                    modifier = Modifier.testTag(EXPORT_SAVE_TAG),
                ) { Text("Save file") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Delivery for a built [NoteExportArtifact]: the share sheet
 * (real file via FileProvider from the cache exports dir) and
 * the system file picker for saving. Neither flow needs any
 * permission.
 */
object NoteExportDelivery {

    /** Writes the artifact to the cache exports dir FileProvider exposes. */
    fun cacheFile(context: Context, artifact: NoteExportArtifact): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, artifact.fileName)
        file.writeText(artifact.content, Charsets.UTF_8)
        return file
    }

    /** Opens the share sheet with the artifact attached as a real file. */
    fun share(context: Context, artifact: NoteExportArtifact) {
        val file = cacheFile(context, artifact)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, artifact.fileName)
            putExtra(Intent.EXTRA_SUBJECT, artifact.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share note").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    /** The ACTION_CREATE_DOCUMENT intent for the Save flow (per-format MIME). */
    fun createDocumentIntent(artifact: NoteExportArtifact): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = artifact.mimeType
            putExtra(Intent.EXTRA_TITLE, artifact.fileName)
        }

    /** Writes the artifact's content to a picker-returned document URI. */
    fun writeTo(context: Context, uri: Uri, artifact: NoteExportArtifact) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Could not open the chosen file for writing.")
        stream.use { it.write(artifact.content.toByteArray(Charsets.UTF_8)) }
    }
}
