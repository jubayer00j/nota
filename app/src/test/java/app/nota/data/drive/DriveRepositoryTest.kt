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

}
