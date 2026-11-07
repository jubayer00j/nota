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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Settings → Sync & backup → Google Drive (Phase 3b): connect
 * (drive.file only), manual backup/restore against the shared
 * canonical archive, and scheduled auto-backup. Phase 6 polish:
 * demoted to a sub-group of Sync & backup, and the backup-
 * encryption record UI moved to Security & privacy
 * ([BackupEncryptionBlock]) — the passphrase controls live with
 * the other lock controls now.
 *
 * Restore goes through [PendingRestoreStore], so the summary +
 * Merge/Replace UI below (BackupSection) is shared with local
 * restores, lock interruptions included.
 */
@Composable
fun DriveBackupSection() {
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

    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Column {
        Text(
            "Google Drive",
            style = MaterialTheme.typography.titleSmall,
        )
        if (signedIn == null) {
            Text(
                "Nota backs up to the same \"Noma Backups\" folder your other devices use. " +
                    "You're not signed in — use Sign in in the Account section " +
                    "at the top of Settings to connect Google Drive for this library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

                TextButton(onClick = { showDisconnectConfirm = true }) {
                    Text("Disconnect Drive")
                }
            }
        }
        Spacer(Modifier.height(NotaSpacing.sm))
    }

    // ---- dialogs ----------------------------------------------------
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
