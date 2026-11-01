package app.nota.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.nota.data.backup.BackupKeyNeededError
import app.nota.data.backup.BackupReader
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.RestoreMode
import app.nota.ui.theme.NotaSpacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Settings → Sync & backup (Phase 2c): local ZIP export (plaintext or
 * NOMAENC1-encrypted) and restore with the 1.x UX shape — read first,
 * show a summary, and only write after the user picks Merge or Replace.
 * SAF cancellation simply returns to idle; nothing is written.
 */
@Composable
fun BackupSection(
    backupRepository: BackupRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPassphrase by remember { mutableStateOf<String?>(null) }
    var showEncryptedExportDialog by remember { mutableStateOf(false) }
    var passphrasePromptFor by remember { mutableStateOf<File?>(null) }
    var restoreSummary by remember { mutableStateOf<BackupReader.Handle?>(null) }
    var restoreTempFile by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) {
            pendingPassphrase = null
            return@rememberLauncherForActivityResult
        }
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        busy = true
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    backupRepository.exportTo(out, passphrase)
                } ?: error("Could not open the chosen file.")
                message = if (passphrase == null) "Backup exported." else "Encrypted backup exported."
            } catch (e: Exception) {
                message = e.message ?: "Export failed."
            } finally {
                busy = false
            }
        }
    }

    fun startRead(tempFile: File, passphrase: String?) {
        busy = true
        scope.launch {
            try {
                val handle = backupRepository.readBackup(tempFile, passphrase)
                restoreTempFile = tempFile
                restoreSummary = handle
            } catch (e: BackupKeyNeededError) {
                passphrasePromptFor = tempFile
                restoreTempFile = tempFile
            } catch (e: Exception) {
                tempFile.delete()
                message = e.message ?: "That backup couldn't be read."
            } finally {
                busy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the chosen file.")
                    backupRepository.copyToTempFile(input)
                }
                // readBackup manages its own busy state from here.
                busy = false
                startRead(tempFile, null)
            } catch (e: Exception) {
                busy = false
                message = e.message ?: "That backup couldn't be read."
            }
        }
    }

    Column {
        Text(
            "Sync & backup",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Back up this library to a local file — the same ZIP format " +
                "as Noma 1.x, so backups move both ways. There is no cloud " +
                "sync in this phase.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Spacer(Modifier.height(NotaSpacing.sm))
            CircularProgressIndicator()
        }
        TextButton(
            onClick = {
                pendingPassphrase = null
                exportLauncher.launch("Noma Backup.zip")
            },
            enabled = !busy,
        ) { Text("Export backup") }
        TextButton(
            onClick = { showEncryptedExportDialog = true },
            enabled = !busy,
        ) { Text("Export encrypted backup") }
        TextButton(
            onClick = {
                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            enabled = !busy,
        ) { Text("Restore backup") }
        if (message != null) {
            Text(
                message!!,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = NotaSpacing.xs),
            )
        }
    }

    // ---- Encrypted export: passphrase + confirm, 1.x no-recovery copy.
    if (showEncryptedExportDialog) {
        var pass1 by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showEncryptedExportDialog = false },
            title = { Text("Export encrypted backup") },
            text = {
                Column {
                    Text(
                        "The whole backup is sealed with this passphrase. " +
                            "There is no recovery: if you forget it, the " +
                            "backup can never be opened — by anyone.",
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    OutlinedTextField(
                        value = pass1,
                        onValueChange = { pass1 = it },
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pass1.isEmpty() -> error = "Enter a passphrase."
                        pass1 != pass2 -> error = "Passphrases don't match."
                        else -> {
                            showEncryptedExportDialog = false
                            pendingPassphrase = pass1
                            exportLauncher.launch("Noma Backup.zip")
                        }
                    }
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showEncryptedExportDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ---- Restore passphrase prompt (encrypted archive).
    val promptFile = passphrasePromptFor
    if (promptFile != null) {
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = {
                passphrasePromptFor = null
                promptFile.delete()
                restoreTempFile = null
            },
            title = { Text("Encrypted backup") },
            text = {
                Column {
                    Text("This backup is encrypted. Enter its passphrase to continue.")
                    Spacer(Modifier.height(NotaSpacing.sm))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val handle = backupRepository.readBackup(promptFile, pass)
                            passphrasePromptFor = null
                            restoreSummary = handle
                        } catch (e: Exception) {
                            error = e.message ?: "That passphrase didn't open the backup."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = {
                    passphrasePromptFor = null
                    promptFile.delete()
                    restoreTempFile = null
                }) { Text("Cancel") }
            },
        )
    }

    // ---- Restore summary + mode pick: NOTHING is written before this.
    val summary = restoreSummary
    if (summary != null) {
        val manifest = summary.manifest
        AlertDialog(
            onDismissRequest = {
                summary.close()
                restoreSummary = null
                restoreTempFile?.delete()
                restoreTempFile = null
            },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    buildString {
                        append("Created: ").append(manifest.createdAt ?: "unknown").append('\n')
                        append("Notes: ").append(manifest.noteCount)
                        append(" · Folders: ").append(manifest.folderCount)
                        append(" · Tags: ").append(manifest.tagCount).append('\n')
                        append("Attachments: ").append(manifest.attachmentCount)
                        append(" · Reminders: ").append(manifest.reminderCount).append('\n')
                        append(if (summary.encrypted) "Encrypted backup." else "Plain backup.")
                        append("\n\nMerge keeps the newest version of each item. ")
                        append("Replace wipes this library and loads the backup wholesale.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val counts = backupRepository.applyBackup(summary, RestoreMode.MERGE)
                            message = "Restored (merge): ${counts.added} added, " +
                                "${counts.updated} updated, ${counts.deleted} deleted."
                        } catch (e: Exception) {
                            message = e.message ?: "Restore failed."
                        } finally {
                            summary.close()
                            restoreSummary = null
                            restoreTempFile?.delete()
                            restoreTempFile = null
                            busy = false
                        }
                    }
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val counts = backupRepository.applyBackup(summary, RestoreMode.REPLACE)
                            message = "Restored (replace): ${counts.added} added."
                        } catch (e: Exception) {
                            message = e.message ?: "Restore failed."
                        } finally {
                            summary.close()
                            restoreSummary = null
                            restoreTempFile?.delete()
                            restoreTempFile = null
                            busy = false
                        }
                    }
                }) { Text("Replace") }
            },
        )
        // Cancel is the dialog's dismiss path (tap outside / back), which
        // closes the handle and deletes the temp copy without writing.
    }
}
