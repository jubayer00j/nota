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
 * Drive coordinator, sync + setup half of the split
 * DriveRepositoryTest (see [DriveRepositoryTestBase]): the
 * shared-archive overwrite guard, scheduled-run serialisation,
 * the sync cycles and encryption setup/adoption. Runs alone
 * in test-fast-b6.
 */
@RunWith(RobolectricTestRunner::class)
class DriveSyncSetupTest : DriveRepositoryTestBase() {

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
