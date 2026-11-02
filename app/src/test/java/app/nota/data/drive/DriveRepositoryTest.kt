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

        override suspend fun findOrCreateFolder(token: String): String = "folder-1"
        override suspend fun findCanonicalBackup(token: String, folderId: String) = canonical
        override suspend fun getMeta(token: String, fileId: String) = canonical
        override suspend fun download(token: String, fileId: String, out: File) {
            out.writeBytes(downloadBytes ?: error("no download bytes set"))
        }

        override suspend fun upload(
            token: String,
            folderId: String,
            existingId: String?,
            file: File,
        ): DriveFileMeta {
            uploadedBytes = file.readBytes()
            uploadedExistingId = existingId
            return DriveFileMeta("file-1", "Noma Backup.zip", 0L, 0L, null, uploadedBytes!!.size.toLong())
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
}
