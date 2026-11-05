package app.nota.feature.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nota.data.auth.AuthManager
import app.nota.data.auth.Session
import app.nota.data.drive.DriveBackupScheduler
import app.nota.data.drive.DriveRepository
import app.nota.data.preferences.PreferencesRepository
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → Google Drive (Phase 3b): connect (drive.file only),
 * manual backup/restore against the shared canonical archive,
 * scheduled auto-backup, and the backup-encryption record UI
 * (setup / unlock / change / remove-key / turn-off — 1.x D3/D4
 * semantics with the device-key arrangement stated up front).
 *
 * Restore goes through [PendingRestoreStore], so the summary +
 * Merge/Replace UI below (BackupSection) is shared with local
 * restores, lock interruptions included.
 */
@Composable
fun DriveBackupSection(onOpenSignIn: () -> Unit) {
    val drive = koinInject<DriveRepository>()
    val auth = koinInject<AuthManager>()
    val prefs = koinInject<PreferencesRepository>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by auth.session.collectAsState()
    val driveState by drive.state.collectAsState()
    val syncStatus by drive.syncStatus.collectAsState()
    val mode by prefs.driveBackupMode.collectAsState(initial = PreferencesRepository.DRIVE_MODE_OFF)
    val intervalHours by prefs.driveBackupIntervalHours.collectAsState(initial = 24)

    val signedIn = session as? Session.SignedIn
    val grantKnown by produceState(false, signedIn?.uid, driveState) {
        value = drive.hasGrant()
    }
    // The record comes from the repository's StateFlow, refreshed on
    // every encryption mutation — a one-shot preferences snapshot here
    // used to keep showing the pre-setup state ("Off") after a
    // successful setup until some unrelated Drive state changed.
    val record by drive.encryptionRecordFlow.collectAsState()
    LaunchedEffect(signedIn?.uid) { drive.refreshEncryptionRecord() }
    val lastBackup by produceState<Pair<Long, String?>?>(null, signedIn?.uid, driveState) {
        value = signedIn?.uid?.let { prefs.getDriveLastBackup(it) }
    }

    // Consent: GMS sometimes answers "ask the user" instead of a
    // token — launch its resolution intent, then resume the op.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> drive.onConsentResult(result.resultCode == Activity.RESULT_OK) }
    val pendingConsent = (driveState as? DriveRepository.State.NeedsConsent)?.intentSender
    LaunchedEffect(pendingConsent) {
        if (pendingConsent != null) {
            consentLauncher.launch(IntentSenderRequest.Builder(pendingConsent).build())
        }
    }
    // Keep the WorkManager schedule in step with the persisted mode.
    LaunchedEffect(mode, intervalHours) {
        DriveBackupScheduler.sync(context, mode, intervalHours)
    }

    var showSetup by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showRemoveKeyConfirm by remember { mutableStateOf(false) }
    var showTurnOffConfirm by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<String?>(null) }
    var opBusy by remember { mutableStateOf(false) }

    Column {
        Text(
            "Google Drive",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (signedIn == null) {
            Text(
                "Nota backs up to the same \"Noma Backups\" folder your other devices use — " +
                    "sign in to connect Google Drive for this library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSignIn) { Text("Sign in") }
        } else {
            val working = driveState as? DriveRepository.State.Working
            if (working != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = NotaSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(working.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when (val s = driveState) {
                is DriveRepository.State.Done ->
                    DriveStatusText(s.message, MaterialTheme.colorScheme.primary)
                is DriveRepository.State.Failed ->
                    DriveStatusText(s.message, MaterialTheme.colorScheme.error)
                is DriveRepository.State.Paused ->
                    DriveStatusText(s.message, MaterialTheme.colorScheme.tertiary)
                else -> {}
            }

            Text(
                when {
                    !grantKnown ->
                        "Not connected yet. Nota can only see files it creates in Drive (drive.file)."
                    lastBackup != null && lastBackup!!.first > 0L ->
                        "Last backup from this device: ${relativeTime(lastBackup!!.first)}"
                    else -> "Connected. No backup from this device yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!grantKnown) {
                Button(
                    onClick = { drive.startConnect() },
                    enabled = working == null,
                    modifier = Modifier.padding(vertical = NotaSpacing.sm),
                ) { Text("Connect Google Drive") }
            } else {
                Button(
                    onClick = { drive.startBackup() },
                    enabled = working == null,
                    modifier = Modifier.padding(vertical = NotaSpacing.sm),
                ) { Text("Back up now") }
                OutlinedButton(
                    onClick = { drive.startRestore() },
                    enabled = working == null,
                ) { Text("Restore from Drive") }

                // ---- automatic mode ----
                Text(
                    "Automatic backup",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = NotaSpacing.md),
                )
                DriveModeRow(
                    label = "Off",
                    selected = mode == PreferencesRepository.DRIVE_MODE_OFF,
                    onClick = {
                        scope.launch { prefs.setDriveBackupMode(PreferencesRepository.DRIVE_MODE_OFF) }
                    },
                )
                DriveModeRow(
                    label = "Scheduled",
                    selected = mode == PreferencesRepository.DRIVE_MODE_SCHEDULED,
                    onClick = {
                        scope.launch {
                            prefs.setDriveBackupMode(PreferencesRepository.DRIVE_MODE_SCHEDULED)
                        }
                    },
                )
                DriveModeRow(
                    label = "Sync",
                    selected = mode == PreferencesRepository.DRIVE_MODE_SYNC,
                    onClick = {
                        scope.launch {
                            prefs.setDriveBackupMode(PreferencesRepository.DRIVE_MODE_SYNC)
                        }
                    },
                )
                if (mode == PreferencesRepository.DRIVE_MODE_SYNC) {
                    Text(
                        "Sync keeps this library and the shared Drive archive continuously " +
                            "merged — newest edit wins, clashes are kept as conflict copies, " +
                            "deletions travel too. Settings never sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val s = syncStatus) {
                        is DriveRepository.SyncStatus.Synced ->
                            DriveStatusText(
                                "Synced ${relativeTime(s.atMillis)} — ${s.message}",
                                MaterialTheme.colorScheme.primary,
                            )
                        is DriveRepository.SyncStatus.Failed ->
                            DriveStatusText(
                                "Sync problem: ${s.message}",
                                MaterialTheme.colorScheme.error,
                            )
                        is DriveRepository.SyncStatus.Paused ->
                            DriveStatusText(s.message, MaterialTheme.colorScheme.tertiary)
                        DriveRepository.SyncStatus.Running ->
                            DriveStatusText(
                                "Syncing…",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        DriveRepository.SyncStatus.NotRun ->
                            DriveStatusText(
                                "Not synced yet on this device.",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    OutlinedButton(
                        onClick = { drive.startSync() },
                        enabled = working == null,
                        modifier = Modifier.padding(vertical = NotaSpacing.xs),
                    ) { Text("Sync now") }
                }
                if (mode == PreferencesRepository.DRIVE_MODE_SCHEDULED) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NotaSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            1 to "1h", 6 to "6h", 12 to "12h",
                            24 to "Daily", 168 to "Weekly", 720 to "30 days",
                        ).forEach { (hours, label) ->
                            FilterChip(
                                selected = intervalHours == hours,
                                onClick = {
                                    scope.launch { prefs.setDriveBackupIntervalHours(hours) }
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                // ---- backup encryption ----
                Text(
                    "Backup encryption",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = NotaSpacing.md),
                )
                when {
                    record == null -> {
                        Text(
                            "Off. Backups are stored as a readable ZIP, like a local export.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { opError = null; showSetup = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.sm),
                        ) { Text("Set up encryption") }
                    }
                    record!!.deviceKeyBase64 != null -> {
                        Text(
                            "On — this device holds the key, so backups stay automatic. " +
                                "Other devices will ask for your passphrase once.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { opError = null; showChange = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.xs),
                        ) { Text("Change passphrase") }
                        OutlinedButton(
                            onClick = { showRemoveKeyConfirm = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.xs),
                        ) { Text("Remove key from this device") }
                        OutlinedButton(
                            onClick = { showTurnOffConfirm = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.xs),
                        ) { Text("Turn off encryption") }
                    }
                    else -> {
                        Text(
                            "On — but this device doesn't hold the key, so backups are paused " +
                                "and restores will ask for the passphrase.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { opError = null; showUnlock = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.sm),
                        ) { Text("Unlock with passphrase") }
                        OutlinedButton(
                            onClick = { showTurnOffConfirm = true },
                            modifier = Modifier.padding(vertical = NotaSpacing.xs),
                        ) { Text("Turn off encryption") }
                    }
                }

                TextButton(onClick = { showDisconnectConfirm = true }) {
                    Text("Disconnect Drive")
                }
            }
        }
        Spacer(Modifier.height(NotaSpacing.sm))
    }

    // ---- dialogs ----------------------------------------------------
    if (showSetup) {
        EncryptionPassphraseDialog(
            title = "Encrypt Drive backups",
            confirmLabel = "Turn on encryption",
            intro = "Backups will be sealed with a passphrase only you know. There is NO recovery: " +
                "lose the passphrase and encrypted backups can't be opened, ever.\n\n" +
                "This device will hold the key so backups stay automatic — anyone with this " +
                "unlocked device can restore them. Your current Drive backup stays readable " +
                "until the next backup replaces it; other devices will need the passphrase after that.",
            error = opError,
            busy = opBusy,
            withConfirm = true,
            onDismiss = { showSetup = false },
            onSubmit = { passphrase, _ ->
                opBusy = true; opError = null
                scope.launch {
                    try {
                        drive.setupEncryption(passphrase)
                        showSetup = false
                    } catch (e: Exception) {
                        opError = e.message ?: "Couldn't set up encryption."
                    } finally {
                        opBusy = false
                    }
                }
            },
        )
    }
    if (showUnlock) {
        EncryptionPassphraseDialog(
            title = "Unlock backup encryption",
            confirmLabel = "Unlock",
            intro = "Enter the passphrase you set for encrypted backups. This device will hold " +
                "the key afterwards so backups resume automatically.",
            error = opError,
            busy = opBusy,
            withConfirm = false,
            onDismiss = { showUnlock = false },
            onSubmit = { passphrase, _ ->
                opBusy = true; opError = null
                scope.launch {
                    try {
                        if (drive.unlockEncryption(passphrase)) {
                            showUnlock = false
                        } else {
                            opError = "That passphrase doesn't match."
                        }
                    } finally {
                        opBusy = false
                    }
                }
            },
        )
    }
    if (showChange) {
        ChangePassphraseDialog(
            error = opError,
            busy = opBusy,
            onDismiss = { showChange = false },
            onSubmit = { current, new ->
                opBusy = true; opError = null
                scope.launch {
                    try {
                        if (drive.changePassphrase(current, new)) {
                            showChange = false
                        } else {
                            opError = "The current passphrase doesn't match."
                        }
                    } finally {
                        opBusy = false
                    }
                }
            },
        )
    }
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect Google Drive?") },
            text = {
                Text(
                    "Nota forgets this connection and the backup-encryption key on this device. " +
                        "The backup file in Drive is not deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch { drive.disconnect() }
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            },
        )
    }
    if (showRemoveKeyConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveKeyConfirm = false },
            title = { Text("Remove the key from this device?") },
            text = {
                Text(
                    "Encryption stays on, but automatic backups pause and restores will ask " +
                        "for your passphrase until you unlock again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveKeyConfirm = false
                    scope.launch { drive.removeDeviceKey() }
                }) { Text("Remove key") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveKeyConfirm = false }) { Text("Cancel") }
            },
        )
    }
    if (showTurnOffConfirm) {
        AlertDialog(
            onDismissRequest = { showTurnOffConfirm = false },
            title = { Text("Turn off backup encryption?") },
            text = {
                Text(
                    "New backups will be readable ZIPs again. The backup currently in Drive " +
                        "stays encrypted until the next backup replaces it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTurnOffConfirm = false
                    scope.launch { drive.disableEncryption() }
                }) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = { showTurnOffConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DriveStatusText(message: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(vertical = NotaSpacing.xs),
    )
}

@Composable
private fun DriveModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Shared passphrase entry (setup with confirm + acknowledgement, or
 *  single-field unlock). Mirrors 1.x's dialog rules: min 8 chars,
 *  mismatch keeps the fields and says so. */
@Composable
private fun EncryptionPassphraseDialog(
    title: String,
    confirmLabel: String,
    intro: String,
    error: String?,
    busy: Boolean,
    withConfirm: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (passphrase: String, acknowledged: Boolean) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(!withConfirm) }
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; localError = null },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (withConfirm) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; localError = null },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text(
                            "I understand there's no recovery if I lose it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val shown = localError ?: error
                if (shown != null) {
                    Text(shown, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    when {
                        passphrase.length < 8 ->
                            localError = "Use at least 8 characters."
                        withConfirm && confirm != passphrase ->
                            localError = "The passphrases don't match — check and try again."
                        withConfirm && !acknowledged ->
                            localError = "Please confirm you understand there's no recovery."
                        else -> onSubmit(passphrase, acknowledged)
                    }
                },
            ) { Text(if (busy) "Working…" else confirmLabel) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChangePassphraseDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (current: String, new: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change backup passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Existing backups stay openable — only the wrap changes, nothing is re-uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; localError = null },
                    label = { Text("Current passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it; localError = null },
                    label = { Text("New passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; localError = null },
                    label = { Text("Confirm new passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = localError ?: error
                if (shown != null) Text(shown, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    when {
                        newPass.length < 8 -> localError = "Use at least 8 characters."
                        confirm != newPass -> localError = "The new passphrases don't match."
                        else -> onSubmit(current, newPass)
                    }
                },
            ) { Text(if (busy) "Working…" else "Change passphrase") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun relativeTime(thenMillis: Long): String {
    val diff = System.currentTimeMillis() - thenMillis
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}
