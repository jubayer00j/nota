package app.nota.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupManifest
import app.nota.data.backup.BackupWriter
import app.nota.data.backup.BxNote
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteVersionEntity
import app.nota.data.sync.SyncApplier
import app.nota.data.templates.TemplateRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Version history (Phase 6, PR #43) under Robolectric — the 1.x
 * `versions.ts` rules pinned against the real repository:
 *
 * - settled-state snapshots (SETTLE_MS = 60 s) hooked ONLY in the
 *   three user-edit entry points, taken from the STORED row;
 * - no-change saves, creates and direct upserts produce nothing;
 * - the 50-version cap prunes the oldest;
 * - locked notes never snapshot;
 * - restore force-snapshots first (undoable), refuses missing
 *   versions and locked notes, and writes through the normal
 *   derivations (the restored text is searchable);
 * - sync applies and permanent deletion never leave versions
 *   behind; trashing keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersionHistoryRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seeds a stored row directly (bypassing every repository path). */
    private suspend fun seedNote(
        id: String = "n1",
        title: String = "Settled title",
        contentHtml: String = "<p>settled body</p>",
        updatedAt: Long,
        locked: Boolean = false,
    ): NoteEntity {
        val note = NoteEntity(
            id = id,
            title = title,
            contentHtml = contentHtml,
            plainText = "settled body",
            createdAt = updatedAt - 10_000,
            updatedAt = updatedAt,
            locked = locked,
        )
        db.noteDao().upsert(note)
        return note
    }

    private suspend fun versions(noteId: String): List<NoteVersionEntity> =
        db.noteVersionDao().getForNote(noteId)

    // ------------------------------------------------ snapshots

    @Test
    fun editJustInsideSettleWindow_producesNoVersion() = runBlocking {
        val now = System.currentTimeMillis()
        // 5 s inside the window — a margin, so a slow runner can
        // never age the state across the boundary mid-test.
        val stored = seedNote(updatedAt = now - (VersionHistory.SETTLE_MS - 5_000))
        repository.updateRichBody(stored, "Settled title", "<p>edited body</p>")
        assertTrue(versions("n1").isEmpty())
    }

    @Test
    fun editExactlyAtSettleBoundary_snapshotsStoredState() = runBlocking {
        val now = System.currentTimeMillis()
        val settledAt = now - VersionHistory.SETTLE_MS
        val stored = seedNote(updatedAt = settledAt)
        repository.updateRichBody(stored, "New title", "<p>edited body</p>")
        val rows = versions("n1")
        assertEquals(1, rows.size)
        // The version holds the REPLACED state, timestamped by it.
        assertEquals("Settled title", rows[0].title)
        assertEquals("<p>settled body</p>", rows[0].contentHtml)
        assertEquals(settledAt, rows[0].createdAt)
        // And the edit itself landed.
        assertEquals("New title", db.noteDao().getById("n1")!!.title)
    }

    @Test
    fun noChangeSaveOnSettledNote_producesNoVersion() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000)
        repository.updateRichBody(stored, "Settled title", "<p>settled body</p>")
        repository.updateTitle(stored, "Settled title")
        assertTrue(versions("n1").isEmpty())
    }

    @Test
    fun plainBodyEditAlsoSnapshots() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000)
        repository.updateBody(stored, "Settled title", "edited plain body")
        val rows = versions("n1")
        assertEquals(1, rows.size)
        assertEquals("<p>settled body</p>", rows[0].contentHtml)
    }

    @Test
    fun titleOnlyEditOnSettledNote_snapshots() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000)
        repository.updateTitle(stored, "Renamed")
        val rows = versions("n1")
        assertEquals(1, rows.size)
        assertEquals("Settled title", rows[0].title)
    }

    @Test
    fun createProducesNoVersion() = runBlocking {
        val note = repository.createRichNote("Fresh", "<p>brand new</p>")
        assertTrue(versions(note.id).isEmpty())
    }

    @Test
    fun directUpsertOfChangedSettledNote_producesNoVersion() = runBlocking {
        // The hook lives in the edit entry points, NOT in upsert —
        // sync applies, backup restores, lock-session saves and
        // the guest transfer all write through upsert and must
        // never snapshot.
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000)
        repository.upsert(
            stored.copy(
                title = "Written around the hook",
                contentHtml = "<p>written around</p>",
                updatedAt = now,
            ),
        )
        assertTrue(versions("n1").isEmpty())
    }

    @Test
    fun capKeepsNewestFifty() = runBlocking {
        val base = System.currentTimeMillis() - 1_000_000
        // The stored state is NEWER than every seeded version —
        // its snapshot (timestamped by its own updatedAt) leads.
        seedNote(updatedAt = base + 200_000)
        // 55 pre-existing versions, oldest first.
        for (i in 1..55) {
            db.noteVersionDao().insert(
                NoteVersionEntity(
                    id = "v$i",
                    noteId = "n1",
                    title = "Version $i",
                    contentHtml = "<p>v$i</p>",
                    createdAt = base + i * 1_000L,
                ),
            )
        }
        val stored = db.noteDao().getById("n1")!!
        VersionHistory.snapshotIfSettled(db, stored, now = base + 200_000 + VersionHistory.SETTLE_MS)
        val rows = versions("n1")
        assertEquals(VersionHistory.MAX_VERSIONS, rows.size)
        // Newest first: the fresh snapshot leads, the six oldest
        // seeded rows (v1..v6) were pruned.
        assertEquals("<p>settled body</p>", rows.first().contentHtml)
        assertEquals(base + 55_000L, rows[1].createdAt)
        assertEquals(base + 7_000L, rows.last().createdAt)
    }

    @Test
    fun lockedNoteNeverSnapshots() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000, locked = true)
        // Title path (the body paths refuse locked notes outright).
        repository.updateTitle(stored, "Renamed while locked")
        assertTrue(versions("n1").isEmpty())
        // And the engine itself, called directly.
        VersionHistory.snapshotIfSettled(db, stored, now = now)
        VersionHistory.forceSnapshot(db, stored)
        assertTrue(versions("n1").isEmpty())
    }

    // ------------------------------------------------ restore

    @Test
    fun restoreMissingVersion_returnsFalse() = runBlocking {
        seedNote(updatedAt = System.currentTimeMillis())
        assertFalse(repository.restoreVersion("no-such-version"))
    }

    @Test
    fun restoreIntoLockedNote_returnsFalseAndLeavesContent() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now, locked = true)
        db.noteVersionDao().insert(
            NoteVersionEntity(
                id = "v1", noteId = "n1", title = "Old",
                contentHtml = "<p>old body</p>", createdAt = now - 5_000,
            ),
        )
        assertFalse(repository.restoreVersion("v1"))
        assertEquals("<p>settled body</p>", db.noteDao().getById("n1")!!.contentHtml)
        assertEquals(stored.title, db.noteDao().getById("n1")!!.title)
    }

    @Test
    fun restoreWritesVersionAndForceSnapshotsCurrent_evenWhenFresh() = runBlocking {
        val now = System.currentTimeMillis()
        // The current state is FRESH (updatedAt = now): a settled
        // snapshot would skip it, but restore must keep it anyway.
        seedNote(
            title = "Current title",
            contentHtml = "<p>current body</p>",
            updatedAt = now,
        )
        db.noteVersionDao().insert(
            NoteVersionEntity(
                id = "v-old", noteId = "n1", title = "Old title",
                contentHtml = "<p>old zebra body</p>", createdAt = now - 90_000,
            ),
        )

        assertTrue(repository.restoreVersion("v-old"))

        val restored = db.noteDao().getById("n1")!!
        assertEquals("Old title", restored.title)
        assertEquals("<p>old zebra body</p>", restored.contentHtml)
        // Written through the normal derivations: searchable.
        assertEquals(
            listOf("n1"),
            repository.searchNotes("zebra").map { it.id },
        )
        // The pre-restore state is now the newest version.
        val rows = versions("n1")
        assertEquals(2, rows.size)
        assertEquals("Current title", rows[0].title)
        assertEquals("<p>current body</p>", rows[0].contentHtml)

        // Restoring THAT snapshot undoes the restore.
        assertTrue(repository.restoreVersion(rows[0].id))
        val undone = db.noteDao().getById("n1")!!
        assertEquals("Current title", undone.title)
        assertEquals("<p>current body</p>", undone.contentHtml)
    }

    // ------------------------------------------------ other writers

    @Test
    fun syncApplyProducesNoVersions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bxNote = BxNote(
            id = "sync-1", title = "Synced note", content = "<p>synced body</p>",
            createdAt = 10L, updatedAt = 20L, folderId = null,
            tagIds = emptyList(), pinned = false, favorite = false,
            archived = false, deleted = false, deletedAt = null,
            reminderAt = null, wordCount = 1, dailyDate = null,
            lockJson = null, isTemplate = false, dailyDefault = null,
        )
        val merged = BackupWriter.ExportData(
            manifest = BackupManifest(
                backupFormatVersion = 2, ownerId = "uid-1", libraryId = "lib",
                nomaVersion = "0.23.0", createdAt = "2026-11-10T00:00:00Z",
                noteCount = 1, folderCount = 0, tagCount = 0,
                attachmentCount = 0, reminderCount = 0, deviceId = "device-a",
            ),
            folders = emptyList(), tags = emptyList(), reminders = emptyList(),
            syncSyncedAtIso = "2026-11-10T00:00:00Z",
            notes = listOf(bxNote), attachments = emptyList(),
            tombstones = emptyList(),
        )
        SyncApplier(
            context,
            provider,
            AttachmentStore(context, provider),
            TemplateRepository(context),
        ).apply(
            merged = merged,
            remoteHandle = null,
            conflictAttachmentSources = emptyMap(),
            knownEntityIds = emptyMap(),
            mergedAttachmentContent = emptyMap(),
            localAttachmentContent = emptyMap(),
        )
        assertEquals("Synced note", db.noteDao().getById("sync-1")!!.title)
        assertTrue(versions("sync-1").isEmpty())
        assertTrue(db.noteVersionDao().getAll().isEmpty())
    }

    @Test
    fun permanentDeleteClearsVersions_butTrashKeepsThem() = runBlocking {
        val now = System.currentTimeMillis()
        val stored = seedNote(updatedAt = now - 120_000)
        db.noteVersionDao().insert(
            NoteVersionEntity(
                id = "v1", noteId = "n1", title = "Old",
                contentHtml = "<p>old</p>", createdAt = now - 60_000,
            ),
        )
        // Trash: history stays (a restored note keeps its past).
        repository.trash(stored)
        assertEquals(1, versions("n1").size)
        // Permanent deletion: history goes with the note.
        val trashed = db.noteDao().getById("n1")!!
        repository.delete(trashed)
        assertTrue(versions("n1").isEmpty())
    }
}
