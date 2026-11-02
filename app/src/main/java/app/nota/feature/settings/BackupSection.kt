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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.PendingRestoreState
import app.nota.data.backup.PendingRestoreStore
import app.nota.data.backup.RestoreMode
import app.nota.ui.theme.NotaSpacing
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → Sync & backup (Phase 2c): local ZIP export (plaintext or
 * NOMAENC1-encrypted) and restore with the 1.x UX shape — read first,
 * show a summary, and only write after the user picks Merge or Replace.
 * SAF cancellation simply returns to idle; nothing is written.
 *
 * Phase 2.5 (F2): the RESTORE flow no longer keeps its state in this
 * composition. With App Lock on + Lock-after = Immediately, returning
 * from the SAF picker re-locks the app and MainActivity un-composes
 * the whole nav shell — every `remember` here dies, which used to kill
 * the restore silently. The picked file, read progress, passphrase
 * prompt, summary handle and the final outcome now live in the
 * app-scoped [PendingRestoreStore]; this section only renders its
 * stages and forwards intents. After unlock, a fresh composition of
 * this section picks the stage up exactly where it left off. The
 * lock screen itself is untouched: nothing here is composed while
 * locked, so no restore detail can show behind the lock.
 */
/**
 * Process-scoped staging for an in-flight export (Phase 2.5, F2
 * companion — the export mirror of the restore bug): the passphrase
 * used to live only in a `remember`, so the Immediate-relock fired
 * by the CreateDocument picker returning destroyed it, and an
 * ENCRYPTED export silently resumed as a PLAINTEXT one after
 * unlock. The staged value now lives here (process lifetime, never
 * persisted). If a picker result arrives with nothing staged
 * (process death across the picker), the export aborts loudly
 * instead of guessing — it must never silently downgrade.
 */
private object PendingExportStaging {
    var staged: Boolean = false
        private set
    var passphrase: String? = null
        private set

    fun stage(passphrase: String?) {
        staged = true
        this.passphrase = passphrase
    }

    /** Returns (wasStaged, passphrase) and clears the staging. */
    fun take(): Pair<Boolean, String?> {
        val result = staged to passphrase
        clear()
        return result
    }

    fun clear() {
        staged = false
        passphrase = null
    }
}

@Composable
fun BackupSection(
    backupRepository: BackupRepository = koinInject(),
    pendingRestoreStore: PendingRestoreStore = PendingRestoreStore.shared,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPassphrase by remember { mutableStateOf<String?>(null) }
    var showEncryptedExportDialog by remember { mutableStateOf(false) }

    // ---- Restore: state lives in the app-scoped store (see KDoc).
    val pending by pendingRestoreStore.state.collectAsState()
    val restoreBusy = pending is PendingRestoreState.Copying ||
        pending is PendingRestoreState.Reading ||
        pending is PendingRestoreState.Applying

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) {
            PendingExportStaging.clear()
            pendingPassphrase = null
            return@rememberLauncherForActivityResult
        }
        val remembered = pendingPassphrase
        pendingPassphrase = null
        val (wasStaged, stagedPassphrase) = PendingExportStaging.take()
        if (!wasStaged && remembered == null) {
            // The picker result arrived but the export's staging was
            // lost (process death across the picker / lock cycle).
            // Never guess a passphrase: nothing is written, and the
            // user is told, instead of an encrypted export silently
            // degrading to a plaintext one.
            message = "Export was interrupted when the app locked — nothing was written. Please export again."
            return@rememberLauncherForActivityResult
        }
        val passphrase = if (wasStaged) stagedPassphrase else remembered
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

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        // A null URI is a genuine picker cancellation: nothing was
        // picked, nothing is staged, and there is nothing to report.
        if (uri == null) return@rememberLauncherForActivityResult
        // The copy runs on the STORE's scope, not this composition's:
        // the Immediate-relock that fires on picker return destroys
        // this composition, and the copy (and everything after it)
        // must survive that. Only the stream open + temp copy are
        // captured here; the resulting File lives in the store.
        pendingRestoreStore.stageCopy {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the chosen file.")
            backupRepository.copyToTempFile(input)
        }
    }

    // Claim a staged copy for reading whenever this section is
    // composed with one pending — on first pick AND on the fresh
    // composition after an unlock. The store's claim is idempotent:
    // recomposition can never start a second read.
    val needsRead = pending as? PendingRestoreState.NeedsRead
    LaunchedEffect(needsRead?.tempFilePath) {
        val staged = needsRead ?: return@LaunchedEffect
        pendingRestoreStore.performRead {
            backupRepository.readBackup(File(staged.tempFilePath), null)
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
        if (busy || restoreBusy) {
            Spacer(Modifier.height(NotaSpacing.sm))
            CircularProgressIndicator()
        }
        TextButton(
            onClick = {
                pendingPassphrase = null
                PendingExportStaging.stage(null)
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
            enabled = !busy && !restoreBusy &&
                (pending is PendingRestoreState.Idle ||
                    pending is PendingRestoreState.Completed ||
                    pending is PendingRestoreState.Failed),
        ) { Text("Restore backup") }
        if (message != null) {
            Text(
                message!!,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = NotaSpacing.xs),
            )
        }
        // Terminal restore outcomes come from the store, so they are
        // still here — visible — after a lock cycle, and stay until
        // the user dismisses them. No restore attempt ends silently.
        when (val outcome = pending) {
            is PendingRestoreState.Completed -> {
                Text(
                    outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = NotaSpacing.xs),
                )
                TextButton(onClick = { pendingRestoreStore.acknowledge() }) { Text("Dismiss") }
            }
            is PendingRestoreState.Failed -> {
                Text(
                    outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = NotaSpacing.xs),
                )
                TextButton(onClick = { pendingRestoreStore.acknowledge() }) { Text("Dismiss") }
            }
            else -> Unit
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
                            PendingExportStaging.stage(pass1)
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

    // ---- Restore passphrase prompt (encrypted archive). Driven by
    // the store stage, so a lock cycle between picking the file and
    // entering the passphrase re-presents this prompt after unlock
    // instead of dropping the restore. A wrong passphrase lands back
    // here with the error attached (BackupUnlockError), file intact.
    val passphraseStage = pending as? PendingRestoreState.NeedsPassphrase
    if (passphraseStage != null) {
        var pass by remember(passphraseStage.tempFilePath) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingRestoreStore.cancel() },
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
                    if (passphraseStage.error != null) {
                        Text(passphraseStage.error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = File(passphraseStage.tempFilePath)
                    pendingRestoreStore.performRead {
                        backupRepository.readBackup(file, pass)
                    }
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreStore.cancel() }) { Text("Cancel") }
            },
        )
    }

    // ---- Restore summary + mode pick: NOTHING is written before this.
    // The handle (and therefore this dialog) is held by the store, so
    // it too resumes after a lock cycle instead of vanishing.
    val summaryStage = pending as? PendingRestoreState.SummaryReady
        ?: (pending as? PendingRestoreState.Applying)?.let {
            PendingRestoreState.SummaryReady(it.tempFilePath)
        }
    val summary = summaryStage?.let { pendingRestoreStore.summaryHandle }
    if (summaryStage != null && summary != null) {
        val manifest = summary.manifest
        val applying = pending is PendingRestoreState.Applying
        AlertDialog(
            onDismissRequest = {
                if (!applying) pendingRestoreStore.cancel()
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
                TextButton(
                    onClick = {
                        pendingRestoreStore.performApply { handle ->
                            val counts = backupRepository.applyBackup(handle, RestoreMode.MERGE)
                            "Restored (merge): ${counts.added} added, " +
                                "${counts.updated} updated, ${counts.deleted} deleted."
                        }
                    },
                    enabled = !applying,
                ) { Text("Merge") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRestoreStore.performApply { handle ->
                            val counts = backupRepository.applyBackup(handle, RestoreMode.REPLACE)
                            "Restored (replace): ${counts.added} added."
                        }
                    },
                    enabled = !applying,
                ) { Text("Replace") }
            },
        )
        // Cancel is the dialog's dismiss path (tap outside / back),
        // which the store turns into handle-close + temp-delete
        // without writing — same contract as before, now lock-proof.
    }
}
