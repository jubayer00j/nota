package app.nota.data.auth

import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.NotaDatabase
import app.nota.data.db.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The guest→account copy (Phase 3a) — the move Jubayer's real library
 * depends on. The contract under test:
 * - everything arrives (folders, tags, notes, links, attachments),
 *   ids preserved,
 * - the guest library is byte-identical afterwards (copy ≠ move),
 * - account rows that already exist are never overwritten, and a
 *   second run copies nothing (insert-if-absent),
 * - search works on the copied notes (indexes rebuilt, not copied).
 */
@RunWith(RobolectricTestRunner::class)
class GuestLibraryTransferTest {

    private lateinit var provider: DatabaseProvider
    private lateinit var prefs: app.nota.data.preferences.PreferencesRepository
    private lateinit var transfer: GuestLibraryTransfer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        for (account in listOf("guest", "uid-transfer")) {
            context.deleteDatabase(NotaDatabase.databaseName(account))
        }
        provider = DatabaseProvider(context, NotaDatabase.GUEST_ACCOUNT)
        prefs = app.nota.data.preferences.PreferencesRepository(context)
        transfer = GuestLibraryTransfer(context, provider, prefs)
    }

    @After
    fun tearDown() {
        provider.current.close()
    }

    private fun note(id: String, title: String, folderId: String? = null) = NoteEntity(
        id = id,
        folderId = folderId,
        title = title,
        contentHtml = "<p>$title body</p>",
        plainText = "$title body",
        createdAt = 10L,
        updatedAt = 20L,
    )

    private suspend fun seedGuest() {
        val guest = provider.current // starts on guest
        guest.folderDao().upsert(
            FolderEntity(id = "f1", name = "Work", createdAt = 1L, updatedAt = 2L),
        )
        guest.tagDao().upsert(TagEntity(id = "t1", name = "ideas", createdAt = 1L))
        guest.noteDao().upsert(note("n1", "First", folderId = "f1"))
        guest.noteDao().upsert(note("n2", "Second"))
        guest.tagDao().link(NoteTagCrossRef(noteId = "n1", tagId = "t1"))
        guest.attachmentDao().upsert(
            AttachmentEntity(
                id = "a1",
                noteId = "n1",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 42L,
                localPath = "attachments/n1/a1-photo.jpg",
                createdAt = 5L,
            ),
        )
        // The binary itself, at the guest's path.
        val guestFile = java.io.File(
            RuntimeEnvironment.getApplication().filesDir, "attachments/n1/a1-photo.jpg",
        )
        guestFile.parentFile?.mkdirs()
        guestFile.writeBytes("fake-jpeg-bytes".toByteArray())
    }

    @Test
    fun copyMovesEverythingAndLeavesGuestUntouched() = runBlocking {
        seedGuest()
        assertEquals(2, transfer.guestNoteCount())
        // Seed sanity: the attachment row is on the live guest
        // instance (the transfer reads the same file via a fresh
        // handle — the two reads are pinned separately because a
        // cross-database read inside a transaction once hid it).
        assertEquals(1, provider.current.attachmentDao().getAll().size)

        provider.switchTo("uid-transfer")
        // A pre-existing account note with a colliding id must win.
        provider.current.noteDao().upsert(
            note("n2", "Account's own Second"),
        )

        val counts = transfer.copyGuestIntoCurrentAccount()
        assertEquals(1, counts.notes) // only n1 — n2 was already the account's
        assertEquals(1, counts.folders)
        assertEquals(1, counts.tags)
        assertEquals(1, counts.attachments)

        val account = provider.current
        assertEquals("First", account.noteDao().getById("n1")?.title)
        assertEquals("f1", account.noteDao().getById("n1")?.folderId)
        assertEquals("Account's own Second", account.noteDao().getById("n2")?.title)
        assertEquals(
            listOf("t1"),
            account.tagDao().observeAllCrossRefs().first()
                .filter { ref -> ref.noteId == "n1" }.map { it.tagId },
        )
        assertEquals("a1", account.attachmentDao().getById("a1")?.id)

        // Codex P1: the account's attachment is an INDEPENDENT copy —
        // its own path under the account suffix, same bytes; the
        // guest's path and file are untouched, so later lock/unlock
        // or deletion in either library cannot corrupt the other.
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        val accountAttachment = account.attachmentDao().getById("a1")!!
        assertTrue(accountAttachment.localPath.startsWith("attachments/n1~uid-transfer/"))
        assertEquals(
            "fake-jpeg-bytes",
            java.io.File(filesDir, accountAttachment.localPath).readText(),
        )
        assertEquals(
            "fake-jpeg-bytes",
            java.io.File(filesDir, "attachments/n1/a1-photo.jpg").readText(),
        )

        // Search finds the copied note by title (FTS rebuilt, not copied).
        val hits = account.noteDao().searchFts("\"First\"")
        assertEquals(listOf("n1"), hits.map { it.id })

        // Second run: insert-if-absent means nothing copies again.
        val again = transfer.copyGuestIntoCurrentAccount()
        assertTrue(again.isEmpty)

        // The guest library is exactly as it was — copy, never move.
        provider.switchTo(NotaDatabase.GUEST_ACCOUNT)
        assertEquals(2, provider.current.noteDao().getAll().size)
        assertEquals("Second", provider.current.noteDao().getById("n2")?.title)
        assertEquals(1, provider.current.attachmentDao().getAll().size)
    }

    @Test
    fun guestCountReadsThroughAnActiveAccount() = runBlocking {
        seedGuest()
        provider.switchTo("uid-transfer")
        assertEquals(2, transfer.guestNoteCount())
    }
    @Test
    fun biometricCopyFollowsCopiedLockedNote() = runBlocking {
        seedGuest()
        // A locked guest note whose fingerprint copy lives in the
        // guest scope of preferences (the account scope starts empty).
        val guest = provider.current
        guest.noteDao().upsert(
            note("n-locked", "Secret").copy(
                lockRecordJson = """{"v":1,"slots":{},"biometric":true,""" +
                    """"failedAttempts":0,"lockoutUntil":null,"lockedAt":1}""",
            ),
        )
        prefs.setNoteLockBiometricCopy(
            app.nota.data.preferences.PreferencesRepository.GUEST_ACCOUNT_ID,
            "n-locked",
            "sealed-nek-blob",
        )
        provider.switchTo("uid-transfer")
        transfer.copyGuestIntoCurrentAccount()
        assertEquals(
            "sealed-nek-blob",
            prefs.getNoteLockBiometricCopy("uid-transfer", "n-locked"),
        )
        // The guest's own copy stays for the guest library's note.
        assertEquals(
            "sealed-nek-blob",
            prefs.getNoteLockBiometricCopy(
                app.nota.data.preferences.PreferencesRepository.GUEST_ACCOUNT_ID,
                "n-locked",
            ),
        )
    }

    @Test
    fun attachmentCopyFailureAbortsAndInsertsNothing() = runBlocking {
        seedGuest()
        // Sabotage the would-be destination: a DIRECTORY where the
        // duplicated binary must land makes the copy throw.
        provider.switchTo("uid-transfer")
        val blocker = java.io.File(
            RuntimeEnvironment.getApplication().filesDir,
            "attachments/n1~uid-transfer/a1-photo.jpg",
        )
        blocker.mkdirs()
        var thrown: Exception? = null
        try {
            transfer.copyGuestIntoCurrentAccount()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("the copy must abort, not fall back to the guest's path", thrown != null)
        assertEquals(0, provider.current.noteDao().getAll().size)
        assertEquals(0, provider.current.attachmentDao().getAll().size)
        blocker.deleteRecursively()
        // Guest untouched, and the retry (blocker gone) succeeds.
        provider.switchTo(NotaDatabase.GUEST_ACCOUNT)
        assertEquals(2, provider.current.noteDao().getAll().size)
        provider.switchTo("uid-transfer")
        val counts = transfer.copyGuestIntoCurrentAccount()
        assertEquals(2, counts.notes)
        assertEquals(1, counts.attachments)
    }

}
