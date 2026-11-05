package app.nota.data.drive

import android.content.Context
import android.content.IntentSender
import app.nota.data.auth.Session
import app.nota.data.sync.toExportData
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
    private val sessionProvider: () -> Session,
    private val driveAuth: DriveAuth,
    private val gateway: DriveGateway,
    private val backups: BackupRepository,
    private val prefs: PreferencesRepository,
    private val pendingRestore: PendingRestoreStore,
    private val syncApplier: app.nota.data.sync.SyncApplier,
    private val provider: app.nota.data.db.DatabaseProvider,
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

    private enum class Op { ACCESS, BACKUP, RESTORE, SYNC }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var inFlight = false

    @Volatile
    private var pendingOp: Op? = null

    private fun signedInUid(): String? =
        (sessionProvider() as? Session.SignedIn)?.uid

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
            Op.SYNC -> startSync()
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
        refreshEncryptionRecord()
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
            if (record == null && canonical != null) {
                // Another device may have set up backup encryption:
                // the record is deliberately never synced, so this
                // device has none — and exporting plaintext over the
                // shared archive would silently downgrade the whole
                // account's backups (Codex P1). Sniff the archive's
                // header; if it is encrypted, ADOPT the header as a
                // keyless local record (the unlock flow below can
                // then bring this device in) and pause instead of
                // overwriting. A plaintext archive proceeds as before.
                val prefix = gateway.downloadPrefix(token, canonical.id, ARCHIVE_PREFIX_BYTES)
                if (BackupCrypto.isEncryptedPrefix(prefix)) {
                    BackupCrypto.parseHeader(prefix)?.let { header ->
                        prefs.setDriveEncryptionRecord(
                            uid,
                            PreferencesRepository.DriveEncryptionRecord(
                                salt = header.salt,
                                iterations = header.iterations,
                                wrappedKey = header.wrappedKey,
                                deviceKeyBase64 = null,
                            ),
                        )
                        refreshEncryptionRecord()
                    }
                    _state.value = State.Paused(ENCRYPTED_ELSEWHERE_COPY)
                    return
                }
            }
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
        // Serialize with interactive operations (Codex P2): a
        // scheduled run that finds a manual backup/restore in flight
        // defers to a WorkManager retry instead of racing it to the
        // canonical file (an older export finishing last would win).
        if (inFlight) return false
        inFlight = true
        try {
            val uid = signedInUid() ?: return true
            if (prefs.driveBackupModeNow() != PreferencesRepository.DRIVE_MODE_SCHEDULED) return true
            val intervalMillis = prefs.driveBackupIntervalHoursNow().coerceIn(1, 720) * 3_600_000L
            val (lastAt, _) = prefs.getDriveLastBackup(uid)
            if (System.currentTimeMillis() - lastAt < intervalMillis) return true
            runBackup()
            return _state.value !is State.Failed
        } finally {
            inFlight = false
        }
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

    private val _encryptionRecord =
        MutableStateFlow<PreferencesRepository.DriveEncryptionRecord?>(null)

    /**
     * The current account's backup-encryption record, kept fresh by
     * EVERY record mutation in this class (setup / unlock / change /
     * remove-key / disable / disconnect / the backup adopt write) —
     * Settings collects this flow instead of snapshotting preferences
     * once, which used to leave the section rendering the stale
     * pre-setup state ("Off" right after a successful setup —
     * device-proven 2026-11-05).
     */
    val encryptionRecordFlow: StateFlow<PreferencesRepository.DriveEncryptionRecord?> =
        _encryptionRecord.asStateFlow()

    /** Re-reads the record from preferences into [encryptionRecordFlow]. */
    suspend fun refreshEncryptionRecord(): PreferencesRepository.DriveEncryptionRecord? {
        val record = signedInUid()?.let { prefs.getDriveEncryptionRecord(it) }
        _encryptionRecord.value = record
        return record
    }

    suspend fun encryptionRecord(): PreferencesRepository.DriveEncryptionRecord? =
        refreshEncryptionRecord()

    /** First-time setup (PBKDF2-600k — the NOMAENC1 header slot),
     *  and this device keeps the key so backups can run unattended
     *  (1.x D2/D4, with the what-if disclosed in the setup UI).
     *
     *  When the canonical Drive archive is ALREADY encrypted — set up
     *  by 1.x, another device, or an earlier install — setup ADOPTS
     *  that archive's key: the stored record takes the archive's own
     *  header and the BMK unwrapped from it with [passphrase]. The
     *  previous behaviour minted a fresh BMK here without ever looking
     *  at Drive, leaving the device holding a key that could never
     *  open the shared archive — every sync then failed with the
     *  "couldn't be opened" error even though the passphrase was
     *  correct (device-proven 2026-11-05). A fresh BMK is minted only
     *  when there is no encrypted archive to adopt. An encrypted
     *  archive whose header won't parse — damaged, or still uploading
     *  — aborts setup instead of being minted over.
     *
     *  The same adoption REPAIRS a divergent stored record — one whose
     *  header differs from the archive's (e.g. minted by the pre-fix
     *  setup while an encrypted archive already existed): the record is
     *  replaced by the archive's header + unwrapped BMK. A wrong
     *  passphrase changes nothing and says so. */
    suspend fun setupEncryption(passphrase: String) {
        val uid = signedInUid() ?: throw DriveError("Sign in first.")
        val existing = prefs.getDriveEncryptionRecord(uid)

        val token = ensureAccess()
        val folderId = gateway.findOrCreateFolder(token)
        val canonical = gateway.findCanonicalBackup(token, folderId)
        val archiveHeader = canonical?.let {
            val prefix = gateway.downloadPrefix(token, it.id, ARCHIVE_PREFIX_BYTES)
            if (BackupCrypto.isEncryptedPrefix(prefix)) {
                // Magic present but the header won't parse: the archive
                // is damaged or still uploading. Never fall through to
                // the fresh-mint path below — a key minted here would
                // overwrite the archive at the next backup and lock
                // whatever it holds for good.
                BackupCrypto.parseHeader(prefix)
                    ?: throw DriveError(
                        "The backup already in Drive looks damaged or is still uploading, " +
                            "so encryption setup stopped. Nothing was changed.",
                    )
            } else {
                null
            }
        }

        if (archiveHeader != null) {
            if (existing != null &&
                existing.salt == archiveHeader.salt &&
                existing.iterations == archiveHeader.iterations &&
                existing.wrappedKey == archiveHeader.wrappedKey &&
                existing.deviceKeyBase64 != null
            ) {
                throw DriveError("Backup encryption is already set up.")
            }
            // No record yet, a keyless record adopted from this
            // archive, or a divergent record: the passphrase proves
            // itself against the ARCHIVE's header, not the record's.
            val bmk = BackupCrypto.unwrapBmk(archiveHeader, passphrase)
                ?: throw DriveError(
                    "That passphrase doesn't open the backup already in Drive. " +
                        "Nothing was changed.",
                )
            prefs.setDriveEncryptionRecord(
                uid,
                PreferencesRepository.DriveEncryptionRecord(
                    salt = archiveHeader.salt,
                    iterations = archiveHeader.iterations,
                    wrappedKey = archiveHeader.wrappedKey,
                    deviceKeyBase64 = Base64.getEncoder().encodeToString(bmk),
                ),
            )
            bmk.fill(0)
            refreshEncryptionRecord()
            return
        }

        if (existing != null) {
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
        refreshEncryptionRecord()
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
        refreshEncryptionRecord()
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
        refreshEncryptionRecord()
        return true
    }

    suspend fun removeDeviceKey() {
        val uid = signedInUid() ?: return
        prefs.removeDriveDeviceKey(uid)
        refreshEncryptionRecord()
    }

    /** Turn encryption off: the record goes; the cloud backup stays
     *  encrypted until the next (plaintext) backup replaces it. */
    suspend fun disableEncryption() {
        val uid = signedInUid() ?: return
        prefs.clearDriveEncryptionRecord(uid)
        refreshEncryptionRecord()
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

    // ------------------------------------------------------------ sync (Phase 3c)

    /** Sync's own status line — separate from the backup/restore
     *  [state] so a sync result never clobbers a restore outcome. */
    sealed interface SyncStatus {
        data object NotRun : SyncStatus
        data object Running : SyncStatus
        data class Synced(val atMillis: Long, val message: String) : SyncStatus
        data class Failed(val message: String) : SyncStatus

        /** Calm stop (encryption on, no device key) — not an error. */
        data class Paused(val message: String) : SyncStatus
    }

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.NotRun)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** Worker entry for sync mode: the same in-flight serialization
     *  as scheduled backups (Codex P2) — busy means retry later. */
    suspend fun runScheduledSync(): Boolean {
        if (inFlight) return false
        inFlight = true
        try {
            return runSyncCycle()
        } finally {
            inFlight = false
        }
    }

    fun startSync() {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                runSyncCycle()
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * One three-way sync cycle against the shared canonical archive
     * (spec §4.3): fetch remote meta → skip when provably unchanged →
     * download + read (device key when the archive is encrypted) →
     * owner gate → [app.nota.data.sync.SyncEngine.merge] → apply the
     * merged snapshot locally → push the converged library through
     * the normal export path when the remote differs → record the new
     * base (hashes + remote meta + merged tombstones).
     *
     * Own-echo: a push updates the stored remote meta, so the next
     * cycle sees "remote unchanged" and compares local content
     * signatures — a device never re-downloads its own upload.
     *
     * Returns false only for transient failures a WorkManager tick
     * should retry; calm stops (signed out, locked encryption, owner
     * mismatch) return true and surface in [syncStatus].
     */
    suspend fun runSyncCycle(): Boolean {
        val uid = signedInUid() ?: return true
        val record = prefs.getDriveEncryptionRecord(uid)
        val db = provider.current
        val store = app.nota.data.sync.SyncStore(db)
        _syncStatus.value = SyncStatus.Running
        var handle: app.nota.data.backup.BackupReader.Handle? = null
        val staged = File(context.cacheDir, "drive-sync-${System.nanoTime()}.zip")
        val pushTmp = File(context.cacheDir, "drive-sync-push-${System.nanoTime()}.zip")
        try {
            val token = ensureAccess()
            val folderId = gateway.findOrCreateFolder(token)
            val meta = gateway.findCanonicalBackup(token, folderId)
            val base = store.loadBaseState()

            if (meta == null) {
                // First sync ever: the local library becomes the archive.
                val local = backups.collectExport()
                val localHashes = app.nota.data.sync.SyncEngine.snapshotHashes(
                    local, backups.attachmentContentHashes(local),
                )
                pushConverged(uid, record, folderId, token, null, pushTmp, store, localHashes)
                _syncStatus.value = SyncStatus.Synced(
                    System.currentTimeMillis(),
                    "First sync complete — your library is in Drive.",
                )
                return true
            }

            val remoteUnchanged = base.lastRemoteChecksum != null &&
                meta.md5Checksum != null &&
                base.lastRemoteChecksum == meta.md5Checksum &&
                base.lastRemoteModifiedTime == meta.modifiedTimeMillis.toString()
            if (remoteUnchanged) {
                val local = backups.collectExport()
                val localHashes = app.nota.data.sync.SyncEngine.snapshotHashes(
                    local, backups.attachmentContentHashes(local),
                )
                val now = System.currentTimeMillis()
                val localSig = app.nota.data.sync.EntityHash.signature(
                    localHashes, store.tombstones(),
                )
                val baseSig = app.nota.data.sync.EntityHash.signature(
                    base.hashes, store.tombstones(),
                )
                if (localSig == baseSig) {
                    _syncStatus.value = SyncStatus.Synced(now, "Everything is in sync.")
                    return true
                }
                // Local-only changes: the merged result IS local.
                pushConverged(uid, record, folderId, token, meta.id, pushTmp, store, localHashes)
                _syncStatus.value = SyncStatus.Synced(now, "Changes uploaded.")
                return true
            }

            // Remote changed (or first sight of it): download + read.
            gateway.download(token, meta.id, staged)
            val bmk = record?.deviceKeyBase64?.let { Base64.getDecoder().decode(it) }
            handle = when {
                looksEncrypted(staged) -> {
                    if (bmk == null) {
                        _syncStatus.value = SyncStatus.Paused(
                            "The Drive archive is encrypted and this device doesn't hold " +
                                "the key — unlock backup encryption to sync.",
                        )
                        return true
                    }
                    backups.readBackupWithBmk(staged, bmk)
                }
                else -> backups.readBackup(staged, null)
            }
            val remote = handle.toExportData()
            val remoteOwner = remote.manifest.ownerId
            if (remoteOwner != null && remoteOwner != uid) {
                _syncStatus.value = SyncStatus.Failed(
                    "The Drive archive belongs to a different account, so sync stopped " +
                        "without changing anything.",
                )
                return true
            }
            val local = backups.collectExport()
            val stagedById = handle.attachments.associate { it.meta.id to it.stagedFile }
            val remoteContent = remote.attachments.associate { att ->
                att.id to (stagedById[att.id]?.takeIf { it.isFile }
                    ?.let { app.nota.data.sync.EntityHash.sha256Hex(it) } ?: "")
            }
            val now = System.currentTimeMillis()
            val localContent = backups.attachmentContentHashes(local)
            val outcome = app.nota.data.sync.SyncEngine.merge(
                local = local,
                remote = remote,
                baseHashes = base.hashes,
                localAttachmentContent = localContent,
                remoteAttachmentContent = remoteContent,
                nowMillis = now,
                nowIso = java.time.Instant.ofEpochMilli(now).toString(),
                newId = { java.util.UUID.randomUUID().toString() },
            )
            if (outcome.changedVsLocal > 0) {
                syncApplier.applyMerged(outcome.merged, local, remote, outcome, handle, localContent)
            }
            store.replaceTombstones(outcome.mergedTombstones)
            store.purgeOldTombstones(now)
            var pushedMeta: app.nota.data.drive.DriveFileMeta? = null
            if (outcome.changedVsRemote > 0) {
                pushedMeta = pushConverged(
                    uid, record, folderId, token, meta.id, pushTmp, store,
                    outcome.newBaseHashes, saveBase = false,
                )
            }
            val finalMeta = pushedMeta ?: meta
            store.saveBaseState(
                app.nota.data.sync.SyncStore.BaseState(
                    lastSyncAt = now,
                    lastRemoteModifiedTime = finalMeta.modifiedTimeMillis.toString(),
                    lastRemoteChecksum = finalMeta.md5Checksum,
                    hashes = outcome.newBaseHashes,
                ),
            )
            val conflicts = outcome.conflictTitles.size
            _syncStatus.value = SyncStatus.Synced(
                now,
                when {
                    conflicts > 0 ->
                        "Synced — $conflicts note(s) were edited on two devices; " +
                            "both versions were kept as conflict copies."
                    outcome.changedVsLocal > 0 && outcome.changedVsRemote > 0 ->
                        "Synced — changes merged both ways."
                    outcome.changedVsLocal > 0 -> "Synced — new changes downloaded."
                    outcome.changedVsRemote > 0 -> "Synced — changes uploaded."
                    else -> "Everything is in sync."
                },
            )
            return true
        } catch (e: DriveAuthNeededException) {
            pendingOp = Op.SYNC
            _state.value = State.NeedsConsent(e.intentSender)
            _syncStatus.value = SyncStatus.Failed("Drive asked for consent — see above.")
            return true
        } catch (e: PausedSyncException) {
            _syncStatus.value = SyncStatus.Paused(LOCKED_COPY)
            return true
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Failed(friendly(e, "Sync failed."))
            return false
        } finally {
            handle?.close()
            staged.delete()
            pushTmp.delete()
        }
    }

    /**
     * Exports the ACTIVE library (which, post-apply, IS the converged
     * snapshot) and uploads it as the canonical archive, honouring the
     * encryption record exactly like a manual backup: a passphrase
     * library never silently downgrades to plaintext. On success the
     * base state is saved with the fresh remote meta (unless the
     * caller defers it), which is what makes the next cycle's
     * own-echo skip work.
     */
    private suspend fun pushConverged(
        uid: String,
        record: PreferencesRepository.DriveEncryptionRecord?,
        folderId: String,
        token: String,
        existingId: String?,
        tmp: File,
        store: app.nota.data.sync.SyncStore,
        hashes: Map<String, String>,
        saveBase: Boolean = true,
    ): app.nota.data.drive.DriveFileMeta {
        if (record != null) {
            val held = record.deviceKeyBase64
                ?: throw PausedSyncException()
            val bmk = Base64.getDecoder().decode(held)
            val header = BackupCrypto.Header(record.salt, record.iterations, record.wrappedKey)
            backups.exportToFileKeyed(tmp, bmk, header)
        } else {
            backups.exportToFile(tmp, null)
        }
        val meta = gateway.upload(token, folderId, existingId, tmp)
        if (saveBase) {
            store.saveBaseState(
                app.nota.data.sync.SyncStore.BaseState(
                    lastSyncAt = System.currentTimeMillis(),
                    lastRemoteModifiedTime = meta.modifiedTimeMillis.toString(),
                    lastRemoteChecksum = meta.md5Checksum,
                    hashes = hashes,
                ),
            )
        }
        return meta
    }

    /** Internal calm-stop signal for a locked encryption record met
     *  mid-push; converted to a Paused status by the cycle. */
    private class PausedSyncException : Exception()

    companion object {
        const val RESULT_OK = "Backup complete"
        const val ENCRYPTED_ELSEWHERE_COPY =
            "The Drive backup is encrypted — it was set up on another device — " +
                "so it was NOT overwritten. Unlock backup encryption below with " +
                "your passphrase to bring this device in."
        const val ARCHIVE_PREFIX_BYTES = 1024
        const val LOCKED_COPY =
            "Backup encryption is on, but this device doesn't hold the key — " +
                "backups are paused. Unlock encryption below to resume."
    }
}
