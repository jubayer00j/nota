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
 * Split (2026-11-28): the original DriveRepositoryTest class
 * (32 tests, ~133s — the solo test-fast-b5 class) now lives in
 * two subclasses of this base, DriveBackupRestoreTest
 * (test-fast-b5) and DriveSyncSetupTest (test-fast-b6), so each
 * CI job carries half the class. Everything shared — the fake
 * gateway/auth, the fixture setUp and the staging helpers —
 * lives here; this base has no tests of its own and is in no
 * shard.
 */
@RunWith(RobolectricTestRunner::class)
abstract class DriveRepositoryTestBase {

    protected class FakeGateway : DriveGateway {
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

    protected class FakeAuth(context: Context) : DriveAuth(context) {
        override suspend fun accessToken(): String = "token-1"
    }

    protected lateinit var context: Context
    protected lateinit var provider: DatabaseProvider
    protected lateinit var prefs: PreferencesRepository
    protected lateinit var backups: BackupRepository
    protected lateinit var pendingRestore: PendingRestoreStore
    protected lateinit var gateway: FakeGateway
    protected lateinit var drive: DriveRepository

    protected val uid = "uid-drive"

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

    protected suspend fun seedNote(id: String, title: String) {
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

    protected suspend fun deleteNote(id: String) {
        provider.current.noteDao().getById(id)?.let { provider.current.noteDao().delete(it) }
    }

    protected suspend fun awaitDriveDone() {
        val state = withTimeout(30_000) {
            drive.state.first {
                it is DriveRepository.State.Done || it is DriveRepository.State.Failed ||
                    it is DriveRepository.State.Paused
            }
        }
        assertTrue("drive op failed: $state", state is DriveRepository.State.Done)
    }







    // ---- PR #52: a passphrase restore hands the device the key

    /** Drives [store] through the exact sequence BackupSection
     *  performs for a passphrase restore: stage a copy, read with no
     *  passphrase (→ prompt), stage the passphrase, read again with
     *  it, then apply (Merge). Ends at the store's Completed state,
     *  which the store only reaches AFTER the applied hook ran. */
    protected suspend fun drivePassphraseRestoreThroughStore(
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













    // ------------------------------------------------------------ sync (Phase 3c)

    /** Builds a remote archive by hand (another "device" of the SAME
     *  or a different account) and points the fake gateway at it. */
    protected fun stageRemoteArchive(
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

    protected fun bxNote(id: String, title: String, updatedAt: Long) =
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
    protected suspend fun stageEncryptedArchive(
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







    // ---- Jules PR #24 review: unparseable archive + iterations guard
}
