package app.nota.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nota.data.importing.NoteImportRepository
import app.nota.data.importing.PendingImportState
import app.nota.data.importing.PendingImportStore
import app.nota.data.importing.StagedImport
import app.nota.data.importing.StagedImportFile
import app.nota.data.importing.stagingFileName
import app.nota.ui.theme.NotaSpacing
import java.io.File
import org.koin.compose.koinInject

/**
 * Settings → Sync & backup → "Import from…" (1.x ImportSection parity):
 * Google Keep Takeout ZIPs and Markdown/text files (plus ZIPs of them).
 * Importing only ever adds notes — duplicates by (title, createdAt)
 * are skipped, so re-importing the same export is a no-op.
 *
 * Like the restore flow, the run's state lives in the app-scoped
 * [PendingImportStore]: returning from the SAF picker can re-lock the
 * app and destroy this composition, and an import must never die
 * silently with it. This section only renders the store's stages and
 * forwards intents; the terminal summary stays until dismissed.
 * Android has no folder picker in this flow — a folder of Markdown
 * files travels as a ZIP, which preserves its paths (and therefore
 * its folder names and relative image references).
 */
@Composable
fun ImportSection(
    importRepository: NoteImportRepository = koinInject(),
    pendingImportStore: PendingImportStore = PendingImportStore.shared,
) {
    val context = LocalContext.current
    val pending by pendingImportStore.state.collectAsState()
    val busy = pending is PendingImportState.Staging || pending is PendingImportState.Running
    var showUnreadable by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        if (pending is PendingImportState.Completed) showUnreadable = false
    }

    val keepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportStore.stageAndRun(
            stage = {
                val dir = newImportTempDir(context)
                val target = File(dir, "keep-takeout.zip")
                copyPickedFile(context, uri, target)
                StagedImport(tempDir = dir, keepZip = target, files = emptyList())
            },
            run = { input, onProgress ->
                importRepository.importKeepZip(
                    requireNotNull(input.keepZip),
                    onProgress,
                ).toSummary()
            },
        )
    }

    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        pendingImportStore.stageAndRun(
            stage = {
                val dir = newImportTempDir(context)
                val usedNames = mutableSetOf<String>()
                val stagedFiles = uris.map { uri ->
                    val name = pickedDisplayName(context, uri)
                    val target = File(dir, stagingFileName(name, usedNames))
                    copyPickedFile(context, uri, target)
                    StagedImportFile(
                        name = name,
                        file = target,
                        lastModified = pickedLastModified(context, uri),
                    )
                }
                StagedImport(tempDir = dir, keepZip = null, files = stagedFiles)
            },
            run = { input, onProgress ->
                importRepository.importMarkdownSelection(input.files, onProgress).toSummary()
            },
        )
    }

    SettingsCard(title = "Import from…") {
        Text(
            "Importing adds notes — it never replaces or deletes what's here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Spacer(Modifier.height(NotaSpacing.sm))
            CircularProgressIndicator()
        }
        TextButton(
            onClick = {
                keepLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            enabled = !busy,
        ) { Text("Google Keep (.zip)") }
        TextButton(
            onClick = { filesLauncher.launch(arrayOf("*/*")) },
            enabled = !busy,
        ) { Text("Markdown or text files") }

        when (val state = pending) {
            is PendingImportState.Running -> {
                Text(
                    if (state.total > 0) {
                        "Importing ${state.done} / ${state.total}…"
                    } else {
                        "Importing…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = NotaSpacing.xs),
                )
            }
            is PendingImportState.Completed -> {
                val summary = state.summary
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    buildString {
                        append("Imported ${summary.imported} note")
                        if (summary.imported != 1) append("s")
                        if (summary.duplicates > 0) {
                            append(" · skipped ${summary.duplicates} already imported")
                        }
                        if (summary.unreadable.isNotEmpty()) {
                            append(" · ${summary.unreadable.size} file")
                            if (summary.unreadable.size != 1) append("s")
                            append(" couldn't be read")
                        }
                        if (summary.attachmentsSkipped > 0) {
                            append(" · ${summary.attachmentsSkipped} attachment")
                            if (summary.attachmentsSkipped != 1) append("s")
                            append(" skipped")
                        }
                        if (summary.imagesUnresolved > 0) {
                            append(" · ${summary.imagesUnresolved} image")
                            if (summary.imagesUnresolved != 1) append("s")
                            append(" couldn't be found")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = NotaSpacing.xs),
                )
                if (summary.coloredNotes > 0) {
                    Text(
                        "Note colours aren't supported in Nota, so coloured Keep notes " +
                            "were imported without their colour.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.unreadable.isNotEmpty()) {
                    TextButton(onClick = { showUnreadable = !showUnreadable }) {
                        Text("Files that couldn't be read")
                    }
                    if (showUnreadable) {
                        for (file in summary.unreadable) {
                            Text(
                                "${file.name} — ${file.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = NotaSpacing.sm,
                                    top = NotaSpacing.xs,
                                ),
                            )
                        }
                    }
                }
                TextButton(onClick = { pendingImportStore.acknowledge() }) { Text("Dismiss") }
            }
            is PendingImportState.Failed -> {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = NotaSpacing.xs),
                )
                TextButton(onClick = { pendingImportStore.acknowledge() }) { Text("Dismiss") }
            }
            else -> Unit
        }

        Spacer(Modifier.height(NotaSpacing.xs))
        Text(
            "Using Apple Notes? Apple Notes doesn't export files — export your notes " +
                "to Markdown on a Mac (e.g. with the Exporter app), then import them here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun newImportTempDir(context: Context): File =
    File(context.cacheDir, "import-pick-${System.nanoTime()}").apply { mkdirs() }

private fun copyPickedFile(context: Context, uri: Uri, target: File) {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Could not open the chosen file.")
    input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
}

/** The picked file's display name (its identity for titles, folders
 *  and the report); falls back to the URI's last path segment. */
private fun pickedDisplayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
    } catch (e: Exception) {
        // Fall through to the path segment.
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
}

/** Best-effort last-modified for a picked file (0 = unknown; the
 *  parser falls back to now, exactly like 1.x's lastModified rule). */
private fun pickedLastModified(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
}
