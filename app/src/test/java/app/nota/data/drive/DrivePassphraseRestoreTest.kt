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
 * Drive coordinator, passphrase-restore half of
 * [DriveBackupRestoreTest] (see [DriveRepositoryTestBase]):
 * the passphrase-restore key-adoption cases, the
 * plaintext-restore record check and the cross-account
 * apply refusal. Runs alone in test-fast-b8.
 */
@RunWith(RobolectricTestRunner::class)
class DrivePassphraseRestoreTest : DriveRepositoryTestBase() {

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
    fun passphraseRestore_adoptionSkippedWhileDriveOpInFlight() = runBlocking {
        // Codex P2 (R3): adoption joins the class's inFlight
        // protocol. This is the exact world in which
        // passphraseRestore_withNoRecordAndNoArchive_adoptsRestoredPair
        // adopts — no record, no canonical archive — except a manual
        // backup is IN FLIGHT when the restore completes (its upload
        // parked on the gateway gate, the seam
        // scheduledBackupDefersWhileAnotherOpIsInFlight uses), so
        // adoption must skip and leave the record completely
        // untouched: letting it land here is how a backup that
        // snapshotted "no key held" and the record could diverge.
        seedNote("n-skip", "Adoption skipped")
        val archive = File(context.cacheDir, "skip.enc")
        backups.exportToFile(archive, "restore-pass")
        deleteNote("n-skip")
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))
        assertEquals(null, gateway.canonical)

        gateway.uploadGate = kotlinx.coroutines.CompletableDeferred()
        drive.startBackup() // sets inFlight synchronously; upload blocks on the gate

        drivePassphraseRestoreThroughStore(pendingRestore, archive, "restore-pass")

        // The restore itself landed…
        assertEquals("Adoption skipped", provider.current.noteDao().getById("n-skip")?.title)
        // …but adoption skipped: still no record at all.
        assertEquals(null, prefs.getDriveEncryptionRecord(uid))

        gateway.uploadGate?.complete(Unit)
        awaitDriveDone()
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
}
