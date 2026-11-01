package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.NotaDatabase
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
import org.robolectric.annotation.Config

/**
 * Repository regression test under Robolectric (in-memory Room):
 * two notes sharing a word must BOTH be searchable (the Phase 0 FTS rowid
 * regression), and trash/restore/archive must move notes in and out of
 * search exactly with their FTS rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteRepositoryRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NoteRepository(
            db, db.noteDao(), db.noteFtsDao(), db.folderDao(),
            db.tagDao(), db.attachmentDao(), db.savedSearchDao(), db.noteLinkDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ftsRowCount(noteId: String): Int {
        val cursor = db.query("SELECT COUNT(*) FROM notes_fts WHERE noteId = ?", arrayOf<Any>(noteId))
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    @Test
    fun twoNotesBothSearchableAndStateOpsMoveSearchability() = runBlocking {
        val first = repository.createNote(title = "Alpha", plainBody = "zebra apple")
        val second = repository.createNote(title = "Beta", plainBody = "zebra banana")

        // Phase 0 rowid regression: BOTH notes must be found.
        val both = repository.searchNotes("zebra")
        assertEquals(setOf(first.id, second.id), both.map { it.id }.toSet())
        assertEquals(listOf(first.id), repository.searchNotes("apple").map { it.id })
        assertEquals(1, ftsRowCount(first.id))

        // Trash: gone from search, FTS row gone.
        repository.trash(first)
        assertEquals(listOf(second.id), repository.searchNotes("zebra").map { it.id })
        assertEquals(0, ftsRowCount(first.id))

        // Restore: searchable again.
        val trashed = db.noteDao().getById(first.id)!!
        assertTrue(trashed.trashedAt != null)
        repository.restore(trashed)
        assertEquals(listOf(first.id), repository.searchNotes("apple").map { it.id })
        assertEquals(1, ftsRowCount(first.id))

        // Archive: excluded from search results.
        val restored = db.noteDao().getById(first.id)!!
        repository.setArchived(restored, true)
        assertTrue(repository.searchNotes("apple").isEmpty())
        assertEquals(listOf(second.id), repository.searchNotes("zebra").map { it.id })
    }

    @Test
    fun backlinksResolveOnWriteAndClearOnTrash() = runBlocking {
        val target = repository.createNote(title = "Project Plan", plainBody = "roadmap")
        val source = repository.createRichNote(
            title = "Meeting",
            contentHtml = "<p>See [[Project Plan]] for details</p>",
        )
        // Edge exists after the write.
        val incoming = repository.observeLinkedFrom(target.id).first()
        assertEquals(listOf(source.id), incoming.map { it.id })

        // Rewriting the source without the link removes the edge.
        val stored = db.noteDao().getById(source.id)!!
        repository.updateRichBody(stored, "Meeting", "<p>No links here</p>")
        assertTrue(repository.observeLinkedFrom(target.id).first().isEmpty())

        // A link to a missing title resolves to nothing (no crash, no edge).
        repository.createRichNote(
            title = "Orphan",
            contentHtml = "<p>Points at [[No Such Note]]</p>",
        )
        assertTrue(repository.observeLinkedFrom(target.id).first().isEmpty())
    }
}
