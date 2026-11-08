package app.nota.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nota.data.auth.AuthManager
import app.nota.data.auth.Session
import app.nota.data.drive.DriveRepository
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → Security & privacy → Backup encryption (Phase 6
 * polish: MOVED here from inside the Drive block of Sync & backup —
 * the passphrase controls belong with the other lock controls; the
 * 1.x D3/D4 semantics and the record logic are unchanged, only the
 * UI's home moved).
 *
 * The block renders the same states it always did, gated the same
 * way: the record UI exists once Drive is connected for a signed-in
 * account. Before that, the block is an honest status line — it
 * never shows controls that cannot work (Drive guest / not
 * connected), and it never pretends encryption is off when the
 * record simply isn't reachable from here.
 */
@Composable
fun BackupEncryptionBlock(
    drive: DriveRepository = koinInject(),
    auth: AuthManager = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val session by auth.session.collectAsState()
    val signedIn = session as? Session.SignedIn
    // Same computation the block always ran in its old home: the
    // Drive state is a key so a fresh grant (Connect, in Sync &
    // backup) re-reads here without a restart.
    val driveState by drive.state.collectAsState()
    val grantKnown by produceState(false, signedIn?.uid, driveState) {
        value = drive.hasGrant()
    }
    // The record comes from the repository's StateFlow, refreshed on
    // every encryption mutation — a one-shot preferences snapshot
    // here used to keep showing the pre-setup state ("Off") after a
    // successful setup until some unrelated Drive state changed.
    val record by drive.encryptionRecordFlow.collectAsState()
    LaunchedEffect(signedIn?.uid) { drive.refreshEncryptionRecord() }

    var showSetup by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var showRemoveKeyConfirm by remember { mutableStateOf(false) }
    var showTurnOffConfirm by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<String?>(null) }
    var opBusy by remember { mutableStateOf(false) }

    // Phase 6 presentation redesign: no header Text here any more —
    // the Security & privacy sub-screen re-houses this block in a
    // "Backup encryption" card whose serif title names it.
    when {
        signedIn == null -> {
            Text(
                "Your Google Drive backups can be sealed with a passphrase " +
                    "only you know. Sign in and connect Drive (in Sync & " +
                    "backup) to set it up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !grantKnown -> {
            Text(
                "Connect Google Drive in Sync & backup to manage backup encryption.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
