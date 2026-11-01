package app.nota.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import app.nota.data.security.AppLockMethod
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.NoteLockRecord
import app.nota.ui.theme.NotaSpacing

/** The unmissable 1.x warning, shared by setup and management. */
private const val NO_RECOVERY_WARNING =
    "There is NO recovery. If you forget the credential, this note can never be opened again. " +
        "Nota cannot reset it for you."

/**
 * Lock setup (Phase 2b): method choice → set → confirm (PR-A's shared
 * [CredentialSetupForm]) → optional fingerprint toggle when available.
 * Nothing is persisted until the setup form completes, so abandoning
 * the dialog can never leave a note half-locked.
 */
@Composable
fun LockNoteDialog(
    error: String?,
    onDismiss: () -> Unit,
    onLock: (AppLockMethod, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val biometricAvailable = remember(context) { isBiometricAvailable(context) }
    var method by remember { mutableStateOf(AppLockMethod.PASSWORD) }
    var useBiometric by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(NotaSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Lock note", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    NO_RECOVERY_WARNING,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                    AppLockMethod.entries.forEach { candidate ->
                        FilterChip(
                            selected = method == candidate,
                            onClick = { method = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                if (biometricAvailable) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Also unlock with fingerprint",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = useBiometric, onCheckedChange = { useBiometric = it })
                    }
                    Text(
                        "Fingerprint is a convenience only — the credential above " +
                            "is always required as the backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(NotaSpacing.sm))
                CredentialSetupForm(
                    method = method,
                    onComplete = { canonical -> onLock(method, canonical, useBiometric) },
                    onCancel = onDismiss,
                )
                if (error != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Lock management (Phase 2b) — only reachable with an unlocked session
 * (the caller gates it), mirroring 1.x: add/replace a credential, remove
 * a credential (the last slot is refused), fingerprint on/off, and
 * Remove lock (decrypts the note back to plaintext).
 */
@Composable
fun ManageLockDialog(
    record: NoteLockRecord,
    feedback: String?,
    onDismiss: () -> Unit,
    onSetSlot: (AppLockMethod, String) -> Unit,
    onRemoveSlot: (AppLockMethod) -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onRemoveLock: () -> Unit,
) {
    val context = LocalContext.current
    val biometricAvailable = remember(context) { isBiometricAvailable(context) }
    var editingMethod by remember { mutableStateOf<AppLockMethod?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(NotaSpacing.md),
            ) {
                Text("Manage lock", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    NO_RECOVERY_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                Text("Credentials", style = MaterialTheme.typography.titleSmall)
                NoteLockMethod.entries.forEach { method ->
                    val present = record.slots.containsKey(method)
                    val isLast = present && record.slots.size <= 1
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            method.appLockMethod.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (present) {
                            TextButton(onClick = { editingMethod = method.appLockMethod }) {
                                Text("Replace")
                            }
                            TextButton(
                                onClick = { onRemoveSlot(method.appLockMethod) },
                                enabled = !isLast,
                            ) { Text("Remove") }
                        } else {
                            TextButton(onClick = { editingMethod = method.appLockMethod }) {
                                Text("Add")
                            }
                        }
                    }
                    if (present && isLast) {
                        Text(
                            "The last credential can't be removed — a note must always keep one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val editing = editingMethod
                if (editing != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    CredentialSetupForm(
                        method = editing,
                        onComplete = { canonical ->
                            editingMethod = null
                            onSetSlot(editing, canonical)
                        },
                        onCancel = { editingMethod = null },
                    )
                }
                Spacer(Modifier.height(NotaSpacing.md))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Fingerprint unlock", style = MaterialTheme.typography.bodyMedium)
                        if (!biometricAvailable) {
                            Text(
                                "Not available on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = record.biometric,
                        onCheckedChange = { onSetBiometric(it) },
                        enabled = biometricAvailable || record.biometric,
                    )
                }
                Spacer(Modifier.height(NotaSpacing.md))
                if (confirmRemove) {
                    Text(
                        "Remove the lock? The note and its attachments become readable plaintext again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(onClick = onRemoveLock) {
                            Text("Yes, remove lock", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { confirmRemove = false }) { Text("Keep lock") }
                    }
                } else {
                    TextButton(onClick = { confirmRemove = true }) {
                        Text("Remove lock", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (feedback != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(NotaSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}
