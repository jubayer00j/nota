package app.nota.data.drive

import android.content.Context
import android.content.IntentSender
import app.nota.data.auth.AuthManager
import app.nota.data.backup.BackupCrypto
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.PendingRestoreState
import app.nota.data.backup.PendingRestoreStore
import app.nota.data.preferences.PreferencesRepository
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Drive coordinator (Phase 3b) — app-scoped like
 * [PendingRestoreStore], so an operation started in Settings survives
 * navigation AND an App Lock interruption, and always ends in a
 * visible state ([State.Done] / [State.Failed] / [State.Paused]),
 * never silence (the Phase 2.5 lesson).
 *
 * Backup encryption (1.x D2–D4): one long-lived BMK per account,
 * wrapped by the user's passphrase into the NOMAENC1 header; the
 * record lives ONLY in this device's preferences (never backed up,
 * never synced). "This device holds the key" makes unattended
 * backups possible; without it, backups pause calmly and restores
 * fall back to asking the passphrase.
 */
class DriveRepository(
    private val context: Context,
    private val sessionProvider: () -> AuthManager.Session,
    private val driveAuth: DriveAuth,
    private val gateway: DriveGateway,
    private val backups: BackupRepository,
    private val prefs: PreferencesRepository,
    private val pendingRestore: PendingRestoreStore,
) {
    sealed interface State {
        data object Idle : State
        data class Working(val label: String) : State
        data class Done(val message: String) : State
        data class Failed(val message: String) : State

        /** Calm, non-error stop: encryption is on but this device
         *  doesn't hold the key (1.x's "locked" state). */
        data class Paused(val message: String) : State

        /** GMS needs the user to consent; the UI launches the sender. */
        data class NeedsConsent(val intentSender: IntentSender) : State
    }

    private enum class Op { ACCESS, BACKUP, RESTORE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var inFlight = false

    @Volatile
    private var pendingOp: Op? = null

    private fun signedInUid(): String? =
        (sessionProvider() as? AuthManager.Session.SignedIn)?.uid

    fun clearTransientState() {
        if (_state.value !is State.Working && _state.value !is State.NeedsConsent) {
            _state.value = State.Idle
        }
    }

    // ------------------------------------------------------------ consent

    /** Connect button: proves/establishes the grant (consent prompt
     *  when GMS asks), so the UI can flip to the connected state. */
    fun startConnect() {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                _state.value = State.Working("Connecting to Google Drive…")
                ensureAccess()
                _state.value = State.Done("Google Drive connected.")
            } catch (e: DriveAuthNeededException) {
                pendingOp = Op.ACCESS
                _state.value = State.NeedsConsent(e.intentSender)
            } catch (e: Exception) {
                _state.value = State.Failed(friendly(e, "Couldn't connect to Google Drive."))
            } finally {
                inFlight = false
            }
        }
    }

    /** The UI's consent launcher finished; on success the interrupted
     *  operation resumes exactly where it stopped. */
    fun onConsentResult(granted: Boolean) {
        val op = pendingOp
        pendingOp = null
        if (!granted) {
            _state.value = State.Failed("Drive access wasn't granted.")
            return
        }
        when (op) {
            Op.BACKUP -> startBackup()
            Op.RESTORE -> startRestore()
            Op.ACCESS, null -> startConnect()
        }
    }

    private suspend fun ensureAccess(): String {
        val uid = signedInUid() ?: throw DriveError("Sign in first — Drive needs your account.")
        val token = driveAuth.accessToken()
        prefs.setDriveGrant(uid, true)
        return token
    }

    suspend fun hasGrant(): Boolean = signedInUid()?.let { prefs.getDriveGrant(it) } ?: false

    /** Explicit disconnect: wipes this account's local Drive
     *  footprint (grant flag, encryption record, status). The backup
     *  file in Drive itself is the user's data — never deleted. */
    suspend fun disconnect() {
        val uid = signedInUid() ?: return
        prefs.clearDriveState(uid)
        _state.value = State.Done("Disconnected from Google Drive. Your backup stays in Drive.")
    }

    // ------------------------------------------------------------ backup

    fun startBackup() {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                runBackup()
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun runBackup() {
        val uid = signedInUid()
        if (uid == null) {
            _state.value = State.Failed("Sign in first — Drive backup needs your account.")
            return
        }
        val record = prefs.getDriveEncryptionRecord(uid)
        var bmk: ByteArray? = null
        var header: BackupCrypto.Header? = null
        if (record != null) {
            val held = record.deviceKeyBase64
            if (held == null) {
                _state.value = State.Paused(LOCKED_COPY)
                return
            }
            bmk = Base64.getDecoder().decode(held)
            header = BackupCrypto.Header(record.salt, record.iterations, record.wrappedKey)
        }
        _state.value = State.Working("Backing up to Google Drive…")
        val tmp = File(context.cacheDir, "drive-backup-${System.nanoTime()}.zip")
        try {
            val token = ensureAccess()
            val folderId = gateway.findOrCreateFolder(token)
            val canonical = gateway.findCanonicalBackup(token, folderId)
            if (bmk != null && header != null) {
                backups.exportToFileKeyed(tmp, bmk, header)
            } else {
                backups.exportToFile(tmp, null)
            }
            gateway.upload(token, folderId, canonical?.id, tmp)
            prefs.setDriveLastBackup(uid, System.currentTimeMillis(), RESULT_OK)
            _state.value = State.Done("Backed up to Google Drive.")
        } catch (e: DriveAuthNeededException) {
            pendingOp = Op.BACKUP
            _state.value = State.NeedsConsent(e.intentSender)
        } catch (e: Exception) {
            _state.value = State.Failed(friendly(e, "Backup to Drive failed."))
        } finally {
            tmp.delete()
        }
    }

    /** Worker entry: one scheduled backup when due. Returns false
     *  only for transient failures worth a WorkManager retry. */
    suspend fun runScheduledBackup(): Boolean {
        val uid = signedInUid() ?: return true
        if (prefs.driveBackupModeNow() != PreferencesRepository.DRIVE_MODE_SCHEDULED) return true
        val intervalMillis = prefs.driveBackupIntervalHoursNow().coerceIn(1, 720) * 3_600_000L
        val (lastAt, _) = prefs.getDriveLastBackup(uid)
        if (System.currentTimeMillis() - lastAt < intervalMillis) return true
        runBackup()
        return _state.value !is State.Failed
    }

    // ------------------------------------------------------------ restore

    fun startRestore() {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                runRestore()
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Downloads the canonical backup and hands it to
     * [PendingRestoreStore] — from there the restore behaves EXACTLY
     * like a local one (passphrase prompt, summary, Merge/Replace,
     * and the 2.9 unlock-resume surface covers it if the app locks
     * mid-flow). When this device holds the encryption key, the
     * archive is claimed for a device-key read straight away: success
     * lands on the summary; a key mismatch falls back to the store's
     * passphrase prompt (another device's archive, rotated key) —
     * never a hard failure.
     */
    private suspend fun runRestore() {
        val uid = signedInUid()
        if (uid == null) {
            _state.value = State.Failed("Sign in first — Drive restore needs your account.")
            return
        }
        _state.value = State.Working("Downloading your Drive backup…")
        val download = File(context.cacheDir, "drive-download-${System.nanoTime()}.zip")
        try {
            val token = ensureAccess()
            val folderId = gateway.findOrCreateFolder(token)
            val canonical = gateway.findCanonicalBackup(token, folderId)
            if (canonical == null) {
                _state.value = State.Failed("No backup found in Google Drive yet.")
                return
            }
            val stage = pendingRestore.state.value
            if (stage !is PendingRestoreState.Idle &&
                stage !is PendingRestoreState.Completed &&
                stage !is PendingRestoreState.Failed
            ) {
                _state.value = State.Failed("Finish the restore already in progress first.")
                return
            }
            gateway.download(token, canonical.id, download)
            pendingRestore.stageCopy {
                download.inputStream().use { backups.copyToTempFile(it) }
            }
            // Device-key fast path: wait for the staging copy to land,
            // then claim the read with the held BMK. Plaintext archives
            // stay on the store's normal path (BackupSection reads
            // them when composed).
            val reached = withTimeoutOrNull(60_000) {
                pendingRestore.state.first {
                    it is PendingRestoreState.NeedsRead || it is PendingRestoreState.Failed
                }
            }
            if (reached is PendingRestoreState.NeedsRead) {
                val record = prefs.getDriveEncryptionRecord(uid)
                val held = record?.deviceKeyBase64
                val staged = File(reached.tempFilePath)
                if (held != null && looksEncrypted(staged)) {
                    val bmk = Base64.getDecoder().decode(held)
                    pendingRestore.performRead { backups.readBackupWithBmk(staged, bmk) }
                }
            }
            _state.value = State.Done("Backup downloaded — check the summary below to restore it.")
        } catch (e: DriveAuthNeededException) {
            pendingOp = Op.RESTORE
            _state.value = State.NeedsConsent(e.intentSender)
        } catch (e: Exception) {
            _state.value = State.Failed(friendly(e, "Couldn't download the Drive backup."))
        } finally {
            download.delete()
        }
    }

    // ------------------------------------------------------------ encryption record

    suspend fun encryptionRecord(): PreferencesRepository.DriveEncryptionRecord? =
        signedInUid()?.let { prefs.getDriveEncryptionRecord(it) }

    /** First-time setup: fresh BMK, wrapped by [passphrase]
     *  (PBKDF2-600k — the NOMAENC1 header slot), and this device
     *  keeps the key so backups can run unattended (1.x D2/D4, with
     *  the what-if disclosed in the setup UI). */
    suspend fun setupEncryption(passphrase: String) {
        val uid = signedInUid() ?: throw DriveError("Sign in first.")
        if (prefs.getDriveEncryptionRecord(uid) != null) {
            throw DriveError("Backup encryption is already set up.")
        }
        val bmk = BackupCrypto.generateBmk()
        val header = BackupCrypto.createHeader(passphrase, bmk)
        prefs.setDriveEncryptionRecord(
            uid,
            PreferencesRepository.DriveEncryptionRecord(
                salt = header.salt,
                iterations = header.iterations,
                wrappedKey = header.wrappedKey,
                deviceKeyBase64 = Base64.getEncoder().encodeToString(bmk),
            ),
        )
        bmk.fill(0)
    }

    /** Passphrase unlock on a device that doesn't hold the key. */
    suspend fun unlockEncryption(passphrase: String): Boolean {
        val uid = signedInUid() ?: return false
        val record = prefs.getDriveEncryptionRecord(uid) ?: return false
        val header = BackupCrypto.Header(record.salt, record.iterations, record.wrappedKey)
        val bmk = BackupCrypto.unwrapBmk(header, passphrase) ?: return false
        prefs.setDriveEncryptionRecord(
            uid,
            record.copy(deviceKeyBase64 = Base64.getEncoder().encodeToString(bmk)),
        )
        bmk.fill(0)
        return true
    }

    /** Re-wrap the SAME BMK under a new passphrase (1.x D3 — existing
     *  archives stay openable; nothing is re-uploaded). */
    suspend fun changePassphrase(current: String, new: String): Boolean {
        val uid = signedInUid() ?: return false
        val record = prefs.getDriveEncryptionRecord(uid) ?: return false
        val header = BackupCrypto.Header(record.salt, record.iterations, record.wrappedKey)
        val bmk = BackupCrypto.unwrapBmk(header, current) ?: return false
        val newHeader = BackupCrypto.createHeader(new, bmk)
        prefs.setDriveEncryptionRecord(
            uid,
            record.copy(
                salt = newHeader.salt,
                iterations = newHeader.iterations,
                wrappedKey = newHeader.wrappedKey,
            ),
        )
        bmk.fill(0)
        return true
    }

    suspend fun removeDeviceKey() {
        val uid = signedInUid() ?: return
        prefs.removeDriveDeviceKey(uid)
    }

    /** Turn encryption off: the record goes; the cloud backup stays
     *  encrypted until the next (plaintext) backup replaces it. */
    suspend fun disableEncryption() {
        val uid = signedInUid() ?: return
        prefs.clearDriveEncryptionRecord(uid)
    }

    private fun friendly(e: Exception, fallback: String): String =
        (e as? DriveError)?.message ?: e.message?.takeIf { it.isNotBlank() } ?: fallback

    /** Magic-byte sniff of a staged file (NOMAENC1 vs plain ZIP). */
    private fun looksEncrypted(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val prefix = ByteArray(8)
            var read = 0
            while (read < prefix.size) {
                val n = input.read(prefix, read, prefix.size - read)
                if (n < 0) break
                read += n
            }
            BackupCrypto.isEncryptedPrefix(prefix.copyOf(read))
        }
    }.getOrDefault(false)

    companion object {
        const val RESULT_OK = "Backup complete"
        const val LOCKED_COPY =
            "Backup encryption is on, but this device doesn't hold the key — " +
                "backups are paused. Unlock encryption below to resume."
    }
}
