package app.nota.data.drive

import android.content.Context
import app.nota.data.attachments.AttachmentStore
import app.nota.data.auth.Session
import app.nota.data.backup.BackupError
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.PendingRestoreState
import app.nota.data.backup.PendingRestoreStore
import app.nota.data.backup.RestoreMode
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NoteEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Drive coordinator (Phase 3b) against a fake gateway — the
 * no-live-account evidence for the shared-archive contract:
 * - backup uploads a readable ZIP to the canonical slot when
 *   encryption is off, and a NOMAENC1 file (openable by passphrase,
 *   i.e. by Noma 1.x) after encryption setup;
 * - a staged Drive restore lands in the SAME PendingRestoreStore
 *   flow as a local restore, and with the device key held it goes
 *   straight to the summary;
 * - an archive owned by a DIFFERENT account is refused at apply.
 */
@RunWith(RobolectricTestRunner::class)
class DriveRepositoryTest {

    private class FakeGateway : DriveGateway {
        var canonical: DriveFileMeta? = null
        var downloadBytes: ByteArray? = null
        var uploadedBytes: ByteArray? = null
        var uploadedExistingId: String? = "unset"
        var uploadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var clock = 1_000L

        override suspend fun findOrCreateFolder(token: String): String = "folder-1"
        override suspend fun findCanonicalBackup(token: String, folderId: String) = canonical
        override suspend fun getMeta(token: String, fileId: String) = canonical
        override suspend fun download(token: String, fileId: String, out: File) {
            out.writeBytes(downloadBytes ?: error("no download bytes set"))
        }

        override suspend fun downloadPrefix(
            token: String,
            fileId: String,
            maxBytes: Int,
        ): ByteArray {
            val bytes = downloadBytes ?: ByteArray(0)
            return bytes.copyOf(minOf(maxBytes, bytes.size))
        }

        override suspend fun upload(
            token: String,
            folderId: String,
            existingId: String?,
            file: File,
        ): DriveFileMeta {
            uploadGate?.await()
            uploadedBytes = file.readBytes()
            uploadedExistingId = existingId
            val md5 = java.security.MessageDigest.getInstance("MD5")
                .digest(uploadedBytes!!)
                .joinToString("") { "%02x".format(it) }
            clock += 1_000L
            val meta = DriveFileMeta(
                "file-1", "Noma Backup.zip", 0L, clock, md5,
                uploadedBytes!!.size.toLong(),
            )
            canonical = meta
            downloadBytes = uploadedBytes
            return meta
        }
    }

    private class FakeAuth(context: Context) : DriveAuth(context) {
        override suspend fun accessToken(): String = "token-1"
    }

    private lateinit var context: Context
    private lateinit var provider: DatabaseProvider
    private lateinit var prefs: PreferencesRepository
    private lateinit var backups: BackupRepository
    private lateinit var pendingRestore: PendingRestoreStore
    private lateinit var gateway: FakeGateway
    private lateinit var drive: DriveRepository

    private val uid = "uid-drive"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        for (account in listOf("guest", uid, "uid-other")) {
            context.deleteDatabase(NotaDatabase.databaseName(account))
        }
        provider = DatabaseProvider(context, NotaDatabase.GUEST_ACCOUNT)
        prefs = PreferencesRepository(context)
        // DataStore state is shared across test methods in the class
        // sandbox: one test's encryption record must never leak into
        // the next test's "encryption off" world.
        runBlocking { prefs.clearDriveState(uid) }
        backups = BackupRepository(
            context,
            provider,
            TemplateRepository(context),
            AttachmentStore(context, provider),
        )
        pendingRestore = PendingRestoreStore()
        gateway = FakeGateway()
        drive = DriveRepository(
            context = context,
            sessionProvider = { Session.SignedIn(uid, "D", "d@example.com", null) },
            driveAuth = FakeAuth(context),
            gateway = gateway,
            backups = backups,
            prefs = prefs,
            pendingRestore = pendingRestore,
            syncApplier = app.nota.data.sync.SyncApplier(
                context,
                provider,
                AttachmentStore(context, provider),
                TemplateRepository(context),
            ),
            provider = provider,
        )
        // Production parity: AuthManager switches the provider to the
        // signed-in account as part of sign-in — mirror that here so
        // exports carry this uid as their manifest owner.
        provider.switchTo(uid)
    }

    private suspend fun seedNote(id: String, title: String) {
        provider.current.noteDao().upsert(
            NoteEntity(
                id = id,
                title = title,
                contentHtml = "<p>$title</p>",
                plainText = title,
                createdAt = 10L,
                updatedAt = 20L,
            ),
        )
    }

    private suspend fun deleteNote(id: String) {
        provider.current.noteDao().getById(id)?.let { provider.current.noteDao().delete(it) }
    }

    private suspend fun awaitDriveDone() {
        val state = withTimeout(30_000) {
            drive.state.first {
                it is DriveRepository.State.Done || it is DriveRepository.State.Failed ||
                    it is DriveRepository.State.Paused
            }
        }
        assertTrue("drive op failed: $state", state is DriveRepository.State.Done)
    }

    @Test
    fun backupUploadsPlaintextZipWhenEncryptionOff() = runBlocking {
        seedNote("n1", "Drive note")
        drive.startBackup()
        awaitDriveDone()
        val bytes = gateway.uploadedBytes
        assertNotNull(bytes)
        assertEquals("PK", String(bytes!!.copyOf(2), Charsets.US_ASCII))
        // No canonical existed: this was a create, not an overwrite.
        assertEquals(null, gateway.uploadedExistingId)
        val (lastAt, result) = prefs.getDriveLastBackup(uid)
        assertTrue(lastAt > 0)
        assertEquals(DriveRepository.RESULT_OK, result)
        assertTrue(drive.hasGrant())
    }

    @Test
    fun backupAfterEncryptionSetupUploadsNomaEnc1OpenableByPassphrase() = runBlocking {
        seedNote("n1", "Secret drive note")
        drive.setupEncryption("passphrase-1")
        drive.startBackup()
        awaitDriveDone()
        val bytes = gateway.uploadedBytes!!
        assertEquals("NOMAENC1", String(bytes.copyOf(8), Charsets.US_ASCII))
        // The passphrase path (what Noma 1.x uses) opens it.
        val file = File(context.cacheDir, "uploaded.enc")
        file.writeBytes(bytes)
        val handle = backups.readBackup(file, "passphrase-1")
        assertEquals(listOf("n1"), handle.notes.map { it.id })
        handle.close()
    }

    @Test
    fun encryptionUnlockAndRewrapKeepTheSameKey() = runBlocking {
        drive.setupEncryption("passphrase-1")
        val recordBefore = prefs.getDriveEncryptionRecord(uid)!!
        val bmkBefore = recordBefore.deviceKeyBase64

        drive.removeDeviceKey()
        assertEquals(null, prefs.getDriveEncryptionRecord(uid)?.deviceKeyBase64)
        assertEquals(false, drive.unlockEncryption("passphrase-wrong"))
        assertEquals(true, drive.unlockEncryption("passphrase-1"))
        assertEquals(bmkBefore, prefs.getDriveEncryptionRecord(uid)?.deviceKeyBase64)

        assertEquals(false, drive.changePassphrase("passphrase-wrong", "passphrase-2"))
        assertEquals(true, drive.changePassphrase("passphrase-1", "passphrase-2"))
        val recordAfter = prefs.getDriveEncryptionRecord(uid)!!
        // Rewrap: header changes, the held device key does not.
        assertTrue(recordAfter.wrappedKey != recordBefore.wrappedKey)
        assertEquals(bmkBefore, recordAfter.deviceKeyBase64)
        assertNotNull(
            app.nota.data.backup.BackupCrypto.unwrapBmk(
                app.nota.data.backup.BackupCrypto.Header(
                    recordAfter.salt, recordAfter.iterations, recordAfter.wrappedKey,
                ),
                "passphrase-2",
            ),
        )
    }

    @Test
    fun backupPausesCalmlyWhenDeviceKeyRemoved() = runBlocking {
        drive.setupEncryption("passphrase-1")
        drive.removeDeviceKey()
        drive.startBackup()
        val state = withTimeout(30_000) {
            drive.state.first { it is DriveRepository.State.Paused || it is DriveRepository.State.Failed }
        }
        assertTrue(state is DriveRepository.State.Paused)
        assertEquals(null, gateway.uploadedBytes)
    }

    @Test
    fun restoreStagesIntoPendingStoreAndSummaryApplies() = runBlocking {
        // Build a real archive from another account's library, then
        // wipe local state — the restore must bring the note back
        // through the shared pending-store flow.
        seedNote("n-remote", "From Drive")
        val archive = File(context.cacheDir, "remote.zip")
        backups.exportToFile(archive, null)
        deleteNote("n-remote")

        gateway.canonical = DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, archive.length())
        gateway.downloadBytes = archive.readBytes()

        drive.startRestore()
        awaitDriveDone()
        val needsRead = withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.NeedsRead }
        } as PendingRestoreState.NeedsRead
        // Exactly the local-restore path from here.
        pendingRestore.performRead { backups.readBackup(File(needsRead.tempFilePath), null) }
        withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.SummaryReady }
        }
        val handle = pendingRestore.summaryHandle!!
        assertEquals(uid, handle.manifest.ownerId)
        val counts = backups.applyBackup(handle, RestoreMode.MERGE)
        assertEquals(1, counts.added)
        assertEquals("From Drive", provider.current.noteDao().getById("n-remote")?.title)
        Unit
    }

    @Test
    fun restoreWithDeviceKeyGoesStraightToSummary() = runBlocking {
        drive.setupEncryption("passphrase-1")
        seedNote("n-enc", "Encrypted remote")
        // Seal an archive under the account's held key (as a Drive
        // backup from this or another of the user's devices would).
        val record = prefs.getDriveEncryptionRecord(uid)!!
        val bmk = Base64.getDecoder().decode(record.deviceKeyBase64)
        val sealed = File(context.cacheDir, "remote-sealed.enc")
        backups.exportToFileKeyed(
            sealed,
            bmk,
            app.nota.data.backup.BackupCrypto.Header(
                record.salt, record.iterations, record.wrappedKey,
            ),
        )
        provider.current.noteDao().getById("n-enc")?.let { provider.current.noteDao().delete(it) }
        gateway.canonical = DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, sealed.length())
        gateway.downloadBytes = sealed.readBytes()

        drive.startRestore()
        // No passphrase prompt: SummaryReady directly.
        withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.SummaryReady }
        }
        assertEquals(
            listOf("n-enc"),
            pendingRestore.summaryHandle!!.notes.map { it.id },
        )
        Unit
    }

    // ---- PR #52: a passphrase restore hands the device the key

    /** Drives [store] through the exact sequence BackupSection
     *  performs for a passphrase restore: stage a copy, read with no
     *  passphrase (→ prompt), stage the passphrase, read again with
     *  it, then apply (Merge). Ends at the store's Completed state,
     *  which the store only reaches AFTER the applied hook ran. */
    private suspend fun drivePassphraseRestoreThroughStore(
        store: PendingRestoreStore,
        archive: File,
        passphrase: String,
    ) {
        store.stageCopy {
            val copy = File(context.cacheDir, "restore-copy-${System.nanoTime()}.bin")
            archive.copyTo(copy)
            copy
        }
        val needsRead = withTimeout(30_000) {
            store.state.first { it is PendingRestoreState.NeedsRead }
        } as PendingRestoreState.NeedsRead
        store.performRead { backups.readBackup(File(needsRead.tempFilePath), null) }
        val needsPassphrase = withTimeout(30_000) {
            store.state.first { it is PendingRestoreState.NeedsPassphrase }
        } as PendingRestoreState.NeedsPassphrase
        store.stageRestorePassphrase(passphrase)
        store.performRead { backups.readBackup(File(needsPassphrase.tempFilePath), passphrase) }
        withTimeout(30_000) {
            store.state.first { it is PendingRestoreState.SummaryReady }
        }
        store.performApply { handle ->
            val counts = backups.applyBackup(handle, RestoreMode.MERGE)
            "Restored (merge): ${counts.added} added."
        }
        withTimeout(30_000) {
            store.state.first { it is PendingRestoreState.Completed }
        }
    }

    @Test
    fun passphraseRestore_withNoRecordAndNoArchive_adoptsRestoredPair() = runBlocking {
        // No encryption record AND no canonical archive in Drive:
        // nothing exists the restored key could conflict with, so
        // the restored pair becomes this device's encryption
        // identity (the fresh-device case).
        seedNote("n-adopt", "Adopt me")
        val archive = File(context.cacheDir, "adopt.enc")
        backups.exportToFile(archive, "restore-pass")
        deleteNote("n-adopt")
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        assertEquals(null, gateway.canonical)

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "restore-pass")

        // The restore itself landed…
        assertEquals("Adopt me", provider.current.noteDao().getById("n-adopt")?.title)
        // …and the device now HOLDS the restored key: the record
        // carries the restored archive's own header, and its device
        // key opens the very same archive (the sync held-key read
        // path).
        val record = prefs.getDriveEncryptionRecord(uid)
        assertNotNull(record)
        assertNotNull(record!!.deviceKeyBase64)
        val adoptedBmk = Base64.getDecoder().decode(record.deviceKeyBase64)
        val reopened = backups.readBackupWithBmk(archive, adoptedBmk)
        assertEquals(listOf("n-adopt"), reopened.notes.map { it.id })
        assertEquals(record.salt, reopened.header!!.salt)
        assertEquals(record.iterations, reopened.header!!.iterations)
        assertEquals(record.wrappedKey, reopened.header!!.wrappedKey)
        reopened.close()
        adoptedBmk.fill(0)
        Unit
    }

    @Test
    fun passphraseRestore_keylessRecordAndMatchingKey_fillsKeyKeepsHeader() = runBlocking {
        // The device has a KEYLESS record (set up, then the device
        // key removed): its header is this device's pointer to the
        // Drive archive's key. The restored backup carries the SAME
        // BMK under a fresh wrap (a re-wrapped header \u2014 different
        // salt), so adoption fills the key and keeps the record's
        // own header byte-identical.
        drive.setupEncryption("restore-pass")
        val setupRecord = prefs.getDriveEncryptionRecord(uid)!!
        val recordBmk = Base64.getDecoder().decode(setupRecord.deviceKeyBase64!!)

        seedNote("n-match", "Same key backup")
        val rewrapped = app.nota.data.backup.BackupCrypto.createHeader("restore-pass", recordBmk)
        val archive = File(context.cacheDir, "match.enc")
        backups.exportToFileKeyed(archive, recordBmk, rewrapped)
        deleteNote("n-match")

        drive.removeDeviceKey()
        val keyless = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(null, keyless.deviceKeyBase64)
        // The backup's wrap is genuinely a different header string.
        assertTrue(rewrapped.wrappedKey != keyless.wrappedKey)

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "restore-pass")

        assertEquals("Same key backup", provider.current.noteDao().getById("n-match")?.title)
        val record = prefs.getDriveEncryptionRecord(uid)!!
        // Header preserved exactly; only the device key was filled,
        // and it is the record's own BMK.
        assertEquals(keyless.salt, record.salt)
        assertEquals(keyless.iterations, record.iterations)
        assertEquals(keyless.wrappedKey, record.wrappedKey)
        assertNotNull(record.deviceKeyBase64)
        val heldBmk = Base64.getDecoder().decode(record.deviceKeyBase64!!)
        assertTrue(heldBmk.contentEquals(recordBmk))
        heldBmk.fill(0)
        recordBmk.fill(0)
        Unit
    }

    @Test
    fun passphraseRestore_keylessRecordAndForeignKey_leavesRecordUntouched() = runBlocking {
        // The Codex R2 case: a LOCAL encrypted export seals under a
        // FRESH BMK every time, so this backup \u2014 sealed with the
        // very same passphrase \u2014 carries a different key than the
        // keyless record's header points to. Adoption must not
        // replace the record: its header is the only pointer to the
        // Drive archive's key, and holding the foreign key would
        // make sync fail against the archive instead of pausing
        // calmly for its passphrase.
        drive.setupEncryption("restore-pass")
        drive.removeDeviceKey()
        val keyless = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(null, keyless.deviceKeyBase64)

        seedNote("n-local", "Local export")
        val archive = File(context.cacheDir, "local.enc")
        backups.exportToFile(archive, "restore-pass")
        deleteNote("n-local")

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "restore-pass")

        // The restore landed; the record is completely untouched
        // \u2014 header preserved, still keyless.
        assertEquals("Local export", provider.current.noteDao().getById("n-local")?.title)
        assertEquals(keyless, prefs.getDriveEncryptionRecord(uid))
        Unit
    }

    @Test
    fun passphraseRestore_archiveUnderDifferentKey_createsNoRecord() = runBlocking {
        // No record, but Drive already holds an archive encrypted
        // under a DIFFERENT key: the restored key is not the
        // archive's, so no record is created (the Security unlock
        // path remains the way in).
        seedNote("n-remote2", "Drive archive note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        stageEncryptedArchive("drive-pass", archiveBmk)

        seedNote("n-local2", "Local backup")
        val archive = File(context.cacheDir, "local2.enc")
        backups.exportToFile(archive, "restore-pass")
        deleteNote("n-local2")
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "restore-pass")

        assertEquals("Local backup", provider.current.noteDao().getById("n-local2")?.title)
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        archiveBmk.fill(0)
        Unit
    }

    @Test
    fun passphraseRestore_archiveUnderSameKey_adoptsArchiveHeader() = runBlocking {
        // No record; the Drive archive and the restored backup
        // carry the SAME BMK (the backup's header is a fresh wrap of
        // it). Adoption creates the record from the ARCHIVE's
        // header \u2014 not the restored file's \u2014 and the held key
        // opens both.
        seedNote("n-shared", "Shared key note")
        val sharedBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        val archiveHeader = stageEncryptedArchive("shared-pass", sharedBmk)

        val rewrapped = app.nota.data.backup.BackupCrypto.createHeader("shared-pass", sharedBmk)
        val archive = File(context.cacheDir, "shared.enc")
        backups.exportToFileKeyed(archive, sharedBmk, rewrapped)
        deleteNote("n-shared")
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "shared-pass")

        assertEquals("Shared key note", provider.current.noteDao().getById("n-shared")?.title)
        val record = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(archiveHeader.salt, record.salt)
        assertEquals(archiveHeader.iterations, record.iterations)
        assertEquals(archiveHeader.wrappedKey, record.wrappedKey)
        assertNotNull(record.deviceKeyBase64)
        val heldBmk = Base64.getDecoder().decode(record.deviceKeyBase64!!)
        assertTrue(heldBmk.contentEquals(sharedBmk))
        // The held key opens the Drive archive itself…
        val sealed = File(context.cacheDir, "shared-drive-open.enc")
        sealed.writeBytes(gateway.downloadBytes!!)
        val driveHandle = backups.readBackupWithBmk(sealed, heldBmk)
        assertEquals(listOf("n-shared"), driveHandle.notes.map { it.id })
        driveHandle.close()
        // …and the restored backup.
        val localHandle = backups.readBackupWithBmk(archive, heldBmk)
        assertEquals(listOf("n-shared"), localHandle.notes.map { it.id })
        localHandle.close()
        heldBmk.fill(0)
        sharedBmk.fill(0)
        Unit
    }

    @Test
    fun passphraseRestore_keepsExistingHeldKeyUntouched() = runBlocking {
        drive.setupEncryption("passphrase-1")
        val recordBefore = prefs.getDriveEncryptionRecord(uid)!!

        seedNote("n-foreign", "Foreign key archive")
        val archive = File(context.cacheDir, "foreign.enc")
        backups.exportToFile(archive, "other-pass")
        deleteNote("n-foreign")

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "other-pass")

        // The restore landed, but the held key is byte-identical:
        // adoption never disturbs a device that already holds one.
        assertEquals(
            "Foreign key archive",
            provider.current.noteDao().getById("n-foreign")?.title,
        )
        assertEquals(recordBefore, prefs.getDriveEncryptionRecord(uid))
        Unit
    }

    @Test
    fun plaintextRestore_createsNoEncryptionRecord() = runBlocking {
        seedNote("n-plain", "Plain archive")
        val archive = File(context.cacheDir, "plain.zip")
        backups.exportToFile(archive, null)
        deleteNote("n-plain")

        pendingRestore.stageCopy {
            val copy = File(context.cacheDir, "plain-copy-${System.nanoTime()}.bin")
            archive.copyTo(copy)
            copy
        }
        val needsRead = withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.NeedsRead }
        } as PendingRestoreState.NeedsRead
        pendingRestore.performRead { backups.readBackup(File(needsRead.tempFilePath), null) }
        withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.SummaryReady }
        }
        pendingRestore.performApply { handle ->
            backups.applyBackup(handle, RestoreMode.MERGE)
            "Restored."
        }
        withTimeout(30_000) {
            pendingRestore.state.first { it is PendingRestoreState.Completed }
        }
        assertEquals("Plain archive", provider.current.noteDao().getById("n-plain")?.title)
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        Unit
    }

    @Test
    fun guestPassphraseRestore_completesAndAdoptsNothing() = runBlocking {
        // A second DriveRepository acting as a guest (no account):
        // records are per-account, so its adoption hook must adopt
        // nothing — while the restore itself completes normally.
        val guestStore = PendingRestoreStore()
        DriveRepository(
            context = context,
            sessionProvider = { Session.Guest },
            driveAuth = FakeAuth(context),
            gateway = gateway,
            backups = backups,
            prefs = prefs,
            pendingRestore = guestStore,
            syncApplier = app.nota.data.sync.SyncApplier(
                context,
                provider,
                AttachmentStore(context, provider),
                TemplateRepository(context),
            ),
            provider = provider,
        )

        seedNote("n-guest", "Guest restore")
        val archive = File(context.cacheDir, "guest.enc")
        backups.exportToFile(archive, "guest-pass")
        deleteNote("n-guest")

        drivePassphraseRestoreThroughStore(guestStore, archive, "guest-pass")

        assertEquals("Guest restore", provider.current.noteDao().getById("n-guest")?.title)
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        Unit
    }

    @Test
    fun applyRefusesAnArchiveOwnedByAnotherAccount() = runBlocking {
        // Export while acting as uid-other (manifest owner = other).
        provider.switchTo("uid-other")
        seedNote("n-other", "Other account note")
        val foreign = File(context.cacheDir, "foreign.zip")
        backups.exportToFile(foreign, null)
        // Apply attempt while acting as uid: refused before any write.
        provider.switchTo(uid)
        val handle = backups.readBackup(foreign, null)
        assertEquals("uid-other", handle.manifest.ownerId)
        try {
            backups.applyBackup(handle, RestoreMode.MERGE)
            fail("expected the owner gate to refuse")
        } catch (e: BackupError) {
            assertTrue(e.message!!.contains("different account"))
        } finally {
            handle.close()
        }
        assertEquals(null, provider.current.noteDao().getById("n-other"))
    }

    // ---- Codex P1: never downgrade another device's encrypted archive

    @Test
    fun backupRefusesToOverwriteEncryptedArchive_andAdoptsItsHeader() = runBlocking {
        val zip = java.io.File(context.cacheDir, "elsewhere-plain.zip")
        zip.writeBytes("PK-elsewhere".toByteArray())
        val sealed = java.io.File(context.cacheDir, "elsewhere-sealed.bin")
        val bmk = app.nota.data.backup.BackupCrypto.generateBmk()
        val header = app.nota.data.backup.BackupCrypto.createHeader("elsewhere-pass", bmk)
        sealed.outputStream().use { out ->
            app.nota.data.backup.BackupCrypto.seal(zip, bmk, header, out)
        }
        bmk.fill(0)
        gateway.canonical = DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, sealed.length())
        gateway.downloadBytes = sealed.readBytes()

        seedNote("n-1", "Local")
        drive.startBackup()
        val state = withTimeout(30_000) {
            drive.state.first { it is DriveRepository.State.Paused }
        }
        assertTrue((state as DriveRepository.State.Paused).message.contains("another device"))
        assertTrue("no upload may happen", gateway.uploadedBytes == null)
        val adopted = prefs.getDriveEncryptionRecord(uid)
        assertTrue(adopted != null && adopted!!.deviceKeyBase64 == null)
        assertTrue(drive.unlockEncryption("elsewhere-pass"))
        drive.clearTransientState() // drop the stale Paused before re-awaiting
        drive.startBackup()
        awaitDriveDone()
        val uploaded = java.io.File(context.cacheDir, "adopted-upload.bin")
        uploaded.writeBytes(gateway.uploadedBytes!!)
        val handle = backups.readBackup(uploaded, "elsewhere-pass")
        assertEquals(1, handle.notes.size)
        handle.close()
    }

    // ---- Codex P2: scheduled runs serialize with interactive ones

    @Test
    fun scheduledBackupDefersWhileAnotherOpIsInFlight() = runBlocking {
        prefs.setDriveBackupMode(PreferencesRepository.DRIVE_MODE_SCHEDULED)
        prefs.setDriveLastBackup(uid, 0L, DriveRepository.RESULT_OK)
        seedNote("n-1", "Local")
        gateway.uploadGate = kotlinx.coroutines.CompletableDeferred()
        drive.startBackup() // sets inFlight synchronously; upload blocks on the gate
        assertTrue(
            "a scheduled run must defer (retry) while a manual op is in flight",
            !drive.runScheduledBackup(),
        )
        gateway.uploadGate?.complete(Unit)
        awaitDriveDone()
    }

    // ------------------------------------------------------------ sync (Phase 3c)

    /** Builds a remote archive by hand (another "device" of the SAME
     *  or a different account) and points the fake gateway at it. */
    private fun stageRemoteArchive(
        owner: String?,
        notes: List<app.nota.data.backup.BxNote>,
        tombstones: List<app.nota.data.backup.BxTombstone> = emptyList(),
    ) {
        val data = app.nota.data.backup.BackupWriter.ExportData(
            manifest = app.nota.data.backup.BackupManifest(
                backupFormatVersion = 2,
                ownerId = owner,
                libraryId = "lib",
                nomaVersion = "0.4.0",
                createdAt = "2026-11-02T00:00:00Z",
                noteCount = notes.size,
                folderCount = 0,
                tagCount = 0,
                attachmentCount = 0,
                reminderCount = 0,
                deviceId = "other-device",
            ),
            folders = emptyList(),
            tags = emptyList(),
            reminders = emptyList(),
            syncSyncedAtIso = "2026-11-02T00:00:00Z",
            notes = notes,
            attachments = emptyList(),
            tombstones = tombstones,
        )
        val buffer = java.io.ByteArrayOutputStream()
        app.nota.data.backup.BackupWriter { null }.write(data, buffer)
        val bytes = buffer.toByteArray()
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        gateway.clock += 5_000L
        gateway.canonical = DriveFileMeta(
            "file-1", "Noma Backup.zip", 0L, gateway.clock, md5, bytes.size.toLong(),
        )
        gateway.downloadBytes = bytes
    }

    private fun bxNote(id: String, title: String, updatedAt: Long) =
        app.nota.data.backup.BxNote(
            id = id,
            title = title,
            content = "<p>$title</p>",
            createdAt = 10L,
            updatedAt = updatedAt,
            folderId = null,
            tagIds = emptyList(),
            pinned = false,
            favorite = false,
            archived = false,
            deleted = false,
            deletedAt = null,
            reminderAt = null,
            wordCount = 1,
            dailyDate = null,
            lockJson = null,
            isTemplate = false,
            dailyDefault = null,
        )
    @Test
    fun sync_firstCycleUploads_secondCycleIsOwnEchoNoOp() = runBlocking {
        seedNote("n-1", "Local")
        assertTrue(drive.runSyncCycle())
        assertTrue(drive.syncStatus.value is DriveRepository.SyncStatus.Synced)
        assertTrue(gateway.uploadedBytes != null)
        gateway.uploadedBytes = null
        // The fake's canonical now describes OUR upload: meta matches
        // the stored base, local content matches too → no download,
        // no upload.
        assertTrue(drive.runSyncCycle())
        assertTrue(
            "own echo must not re-upload",
            gateway.uploadedBytes == null,
        )
    }

    @Test
    fun sync_remoteOnlyNoteDownloadsAndConvergedArchivePushes() = runBlocking {
        seedNote("n-local", "Local")
        assertTrue(drive.runSyncCycle())
        // Another device adds a note to the shared archive.
        stageRemoteArchive(uid, listOf(bxNote("n-remote", "From elsewhere", 50L)))
        gateway.uploadedBytes = null
        assertTrue(drive.runSyncCycle())
        val titles = provider.current.noteDao().getAll().map { it.title }.toSet()
        assertTrue(titles.contains("From elsewhere"))
        assertTrue(titles.contains("Local"))
        // The converged archive (both notes) was pushed back.
        assertTrue(gateway.uploadedBytes != null)
    }

    @Test
    fun sync_ownerMismatchRefusesWithoutTouchingLocal() = runBlocking {
        seedNote("n-local", "Local")
        stageRemoteArchive("uid-other", listOf(bxNote("n-x", "Alien", 50L)))
        assertTrue(drive.runSyncCycle())
        val status = drive.syncStatus.value
        assertTrue(status is DriveRepository.SyncStatus.Failed)
        assertTrue((status as DriveRepository.SyncStatus.Failed).message.contains("different account"))
        val titles = provider.current.noteDao().getAll().map { it.title }
        assertEquals(listOf("Local"), titles)
        assertTrue(gateway.uploadedBytes == null)
    }

    @Test
    fun sync_bothSidesEditMakesConflictCopy() = runBlocking {
        seedNote("n-1", "Original")
        assertTrue(drive.runSyncCycle())
        // Local edit (newer) and a remote edit of the same note.
        provider.current.noteDao().upsert(
            NoteEntity(
                id = "n-1", title = "Local edit", contentHtml = "<p>Local edit</p>",
                plainText = "Local edit", createdAt = 10L, updatedAt = 90L,
            ),
        )
        stageRemoteArchive(uid, listOf(bxNote("n-1", "Remote edit", 80L)))
        assertTrue(drive.runSyncCycle())
        val notes = provider.current.noteDao().getAll()
        val kept = notes.first { it.id == "n-1" }
        assertEquals("Local edit", kept.title)
        val copy = notes.firstOrNull { it.title == "Remote edit (conflict copy)" }
        assertTrue("loser kept as a conflict copy", copy != null)
        val status = drive.syncStatus.value
        assertTrue(status is DriveRepository.SyncStatus.Synced)
        assertTrue(
            (status as DriveRepository.SyncStatus.Synced).message.contains("conflict"),
        )
    }

    @Test
    fun sync_writerReader_tombstonesRoundTrip() {
        val data = app.nota.data.backup.BackupWriter.ExportData(
            manifest = app.nota.data.backup.BackupManifest(
                backupFormatVersion = 2, ownerId = uid, libraryId = "lib",
                nomaVersion = "0.4.0", createdAt = "2026-11-02T00:00:00Z",
                noteCount = 0, folderCount = 0, tagCount = 0,
                attachmentCount = 0, reminderCount = 0, deviceId = "other-device",
            ),
            folders = emptyList(),
            tags = emptyList(),
            reminders = emptyList(),
            syncSyncedAtIso = "2026-11-02T00:00:00Z",
            notes = emptyList(),
            attachments = emptyList(),
            tombstones = listOf(
                app.nota.data.backup.BxTombstone("note", "n-1", 500L, "other-device"),
            ),
        )
        val zipFile = java.io.File(context.cacheDir, "roundtrip.zip")
        java.io.FileOutputStream(zipFile).use { out ->
            app.nota.data.backup.BackupWriter { null }.write(data, out)
        }
        val syncJson = java.util.zip.ZipFile(zipFile).use { zip ->
            zip.getEntry("sync.json")?.let { entry ->
                zip.getInputStream(entry).readBytes().decodeToString()
            }
        }
        assertTrue("sync.json missing tombstone: $syncJson", syncJson?.contains("n-1") == true)
        val handle = app.nota.data.backup.BackupReader(context.cacheDir).read(zipFile, null)
        assertEquals(
            "reader dropped tombstones; sync.json=$syncJson",
            listOf("n-1"), handle.tombstones.map { it.id },
        )
        handle.close()
    }

    @Test
    fun sync_remoteTombstoneDeletesLocalNote() = runBlocking {
        seedNote("n-1", "Doomed")
        assertTrue("cycle1: ${drive.syncStatus.value}", drive.runSyncCycle())
        stageRemoteArchive(
            uid,
            emptyList(),
            tombstones = listOf(
                // A realistic deletion instant: the entity timestamps
                // in these fixtures are tiny synthetics compared only
                // with each other, but tombstones are ALSO judged
                // against wall-clock now by the 90-day retention
                // purge — an epoch-relative deletedAt would be purged
                // as ancient before the merge ever saw it.
                app.nota.data.backup.BxTombstone(
                    "note", "n-1", System.currentTimeMillis(), "other-device",
                ),
            ),
        )
        assertTrue("cycle2: ${drive.syncStatus.value}", drive.runSyncCycle())
        val tombstones = app.nota.data.sync.SyncStore(provider.current).tombstones()
        assertTrue(
            "status=${drive.syncStatus.value} storedTombstones=$tombstones " +
                "notes=${provider.current.noteDao().getAll().map { it.id }}",
            provider.current.noteDao().getAll().isEmpty(),
        )
    }

    // ---- setup adopts the existing archive's key (2026-11-05 fix)
    //
    // Device-proven failure: setup minted a FRESH BMK without looking
    // at Drive, so the device key could never open the archive that
    // was already there — restore (passphrase path) worked, sync
    // (device-key path) failed with "couldn't be opened" forever.

    /** Seals the current library under [archiveBmk] (header wrapped by
     *  [passphrase]) and points the fake gateway at the sealed bytes —
     *  an encrypted canonical archive as 1.x or another device left it.
     *  Returns the archive's header. */
    private suspend fun stageEncryptedArchive(
        passphrase: String,
        archiveBmk: ByteArray,
    ): app.nota.data.backup.BackupCrypto.Header {
        val plain = File(context.cacheDir, "stage-plain.zip")
        backups.exportToFile(plain, null)
        val header = app.nota.data.backup.BackupCrypto.createHeader(passphrase, archiveBmk)
        val sealed = File(context.cacheDir, "stage-sealed.enc")
        sealed.outputStream().use { out ->
            app.nota.data.backup.BackupCrypto.seal(plain, archiveBmk, header, out)
        }
        val bytes = sealed.readBytes()
        gateway.canonical =
            DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, bytes.size.toLong())
        gateway.downloadBytes = bytes
        return header
    }

    @Test
    fun setupAdoptsExistingEncryptedArchiveKey() = runBlocking {
        seedNote("n-remote", "Elsewhere note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        val archiveHeader = stageEncryptedArchive("archive-pass", archiveBmk)

        drive.setupEncryption("archive-pass")

        val record = prefs.getDriveEncryptionRecord(uid)!!
        // The record IS the archive's header — not a fresh local wrap.
        assertEquals(archiveHeader.salt, record.salt)
        assertEquals(archiveHeader.iterations, record.iterations)
        assertEquals(archiveHeader.wrappedKey, record.wrappedKey)
        // …and the held device key is the archive's own BMK, which
        // opens the staged archive end to end (the sync read path).
        val heldBmk = Base64.getDecoder().decode(record.deviceKeyBase64!!)
        assertTrue(heldBmk.contentEquals(archiveBmk))
        val sealed = File(context.cacheDir, "stage-sealed-open.enc")
        sealed.writeBytes(gateway.downloadBytes!!)
        val handle = backups.readBackupWithBmk(sealed, heldBmk)
        assertEquals(listOf("n-remote"), handle.notes.map { it.id })
        handle.close()
        archiveBmk.fill(0)
    }

    @Test
    fun setupWrongPassphraseForExistingArchiveChangesNothing() = runBlocking {
        seedNote("n-remote", "Elsewhere note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        stageEncryptedArchive("archive-pass", archiveBmk)
        try {
            drive.setupEncryption("not-the-passphrase")
            fail("expected the adopt failure to throw")
        } catch (e: DriveError) {
            assertTrue(e.message!!.contains("doesn't open the backup already in Drive"))
        }
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        archiveBmk.fill(0)
    }

    @Test
    fun setupWithNoArchiveMintsFreshKeyAsBefore() = runBlocking {
        drive.setupEncryption("passphrase-1")
        val record = prefs.getDriveEncryptionRecord(uid)!!
        assertNotNull(record.deviceKeyBase64)
        val unwrapped = app.nota.data.backup.BackupCrypto.unwrapBmk(
            app.nota.data.backup.BackupCrypto.Header(
                record.salt, record.iterations, record.wrappedKey,
            ),
            "passphrase-1",
        )
        assertNotNull(unwrapped)
        assertTrue(
            Base64.getDecoder().decode(record.deviceKeyBase64!!).contentEquals(unwrapped!!),
        )
    }

    @Test
    fun setupRepairsDivergentRecordOntoArchiveKey() = runBlocking {
        // The user's live state (2026-11-05): a record minted by the
        // pre-fix setup (fresh BMK_B) while Drive holds an archive
        // sealed under BMK_A — same passphrase, different key.
        drive.setupEncryption("passphrase-1") // no canonical yet → fresh record
        val divergent = prefs.getDriveEncryptionRecord(uid)!!

        seedNote("n-remote", "Elsewhere note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        val archiveHeader = stageEncryptedArchive("passphrase-1", archiveBmk)
        assertTrue(divergent.salt != archiveHeader.salt)

        // NOT "already set up": the passphrase repairs the record
        // onto the archive's own key.
        drive.setupEncryption("passphrase-1")
        val repaired = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(archiveHeader.salt, repaired.salt)
        assertEquals(archiveHeader.wrappedKey, repaired.wrappedKey)
        val heldBmk = Base64.getDecoder().decode(repaired.deviceKeyBase64!!)
        assertTrue(heldBmk.contentEquals(archiveBmk))
        archiveBmk.fill(0)
    }

    @Test
    fun setupWithMatchingRecordStaysAlreadySetUp() = runBlocking {
        seedNote("n-remote", "Elsewhere note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        stageEncryptedArchive("archive-pass", archiveBmk)
        drive.setupEncryption("archive-pass") // adopts → record matches the archive
        try {
            drive.setupEncryption("archive-pass")
            fail("expected the already-set-up guard")
        } catch (e: DriveError) {
            assertEquals("Backup encryption is already set up.", e.message)
        }
        archiveBmk.fill(0)
    }

    @Test
    fun encryptionRecordFlowEmitsAfterSetup() = runBlocking {
        assertEquals(null, drive.encryptionRecordFlow.value)
        drive.setupEncryption("passphrase-1")
        val emitted = drive.encryptionRecordFlow.value
        assertNotNull(emitted)
        val stored = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(stored.salt, emitted!!.salt)
        assertEquals(stored.wrappedKey, emitted.wrappedKey)
        assertEquals(stored.deviceKeyBase64, emitted.deviceKeyBase64)
        // Mutations keep flowing: removing the key shows at once.
        drive.removeDeviceKey()
        assertNotNull(drive.encryptionRecordFlow.value)
        assertEquals(null, drive.encryptionRecordFlow.value?.deviceKeyBase64)
    }

    // ---- Jules PR #24 review: unparseable archive + iterations guard

    @Test
    fun setupAbortsWhenEncryptedArchiveHeaderIsUnparseable() = runBlocking {
        // NOMAENC1 magic present, header garbage: a damaged or
        // half-uploaded archive. Setup must stop and store nothing —
        // minting a fresh key here would overwrite the archive at the
        // next backup and lock whatever it holds for good.
        val headerBytes = "not json!!".toByteArray(Charsets.UTF_8)
        val corrupt = "NOMAENC1".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, headerBytes.size.toByte()) +
            headerBytes
        gateway.canonical =
            DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, corrupt.size.toLong())
        gateway.downloadBytes = corrupt
        try {
            drive.setupEncryption("passphrase-1")
            fail("expected setup to abort on the unparseable archive")
        } catch (e: DriveError) {
            assertTrue(e.message!!.contains("damaged or is still uploading"))
        }
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
    }

    @Test
    fun setupAdoptsWhenRecordIterationsDifferFromArchive() = runBlocking {
        seedNote("n-remote", "Elsewhere note")
        val archiveBmk = app.nota.data.backup.BackupCrypto.generateBmk()
        val archiveHeader = stageEncryptedArchive("archive-pass", archiveBmk)
        // A record matching the archive on salt + wrappedKey and
        // holding a device key, but whose iteration count drifted:
        // NOT "already set up" — setup re-adopts, rewriting the
        // record from the archive's own header.
        prefs.setDriveEncryptionRecord(
            uid,
            PreferencesRepository.DriveEncryptionRecord(
                salt = archiveHeader.salt,
                iterations = archiveHeader.iterations + 1,
                wrappedKey = archiveHeader.wrappedKey,
                deviceKeyBase64 = Base64.getEncoder().encodeToString(archiveBmk),
            ),
        )
        drive.setupEncryption("archive-pass")
        val record = prefs.getDriveEncryptionRecord(uid)!!
        assertEquals(archiveHeader.iterations, record.iterations)
        assertEquals(archiveHeader.salt, record.salt)
        assertEquals(archiveHeader.wrappedKey, record.wrappedKey)
        val heldBmk = Base64.getDecoder().decode(record.deviceKeyBase64!!)
        assertTrue(heldBmk.contentEquals(archiveBmk))
        archiveBmk.fill(0)
    }

}
