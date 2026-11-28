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
 * Drive coordinator, backup + restore half of the split
 * DriveRepositoryTest (see [DriveRepositoryTestBase]): backup
 * uploads, encryption key handling, restore staging and the
 * first passphrase-restore key-adoption cases. (The remaining
 * adoption cases moved to [DrivePassphraseRestoreTest] in the
 * 2026-11-28 b5 split.) Runs alone in test-fast-b5.
 */
@RunWith(RobolectricTestRunner::class)
class DriveBackupRestoreTest : DriveRepositoryTestBase() {

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
}
