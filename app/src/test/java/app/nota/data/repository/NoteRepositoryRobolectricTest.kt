package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.stress.StressLibrary
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
        repository = NoteRepository(DatabaseProvider.forDatabase(context, db))
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

    @Test
    fun backlinksResolveWhenTargetIsCreatedOrRenamed() = runBlocking {
        // Source references a title that does not exist yet.
        val source = repository.createRichNote(
            title = "Source",
            contentHtml = "<p>See [[Future Note]]</p>",
        )
        // Creating the target re-resolves the referencing note (G5a).
        val target = repository.createNote(title = "Future Note", plainBody = "body")
        assertEquals(
            listOf(source.id),
            repository.observeLinkedFrom(target.id).first().map { it.id },
        )
        // Renaming the target away drops the edge without touching the source.
        val stored = db.noteDao().getById(target.id)!!
        repository.updateTitle(stored, "Other Title")
        assertTrue(repository.observeLinkedFrom(target.id).first().isEmpty())
        // Renaming it back re-resolves the reference again.
        val renamed = db.noteDao().getById(target.id)!!
        repository.updateTitle(renamed, "Future Note")
        assertEquals(
            listOf(source.id),
            repository.observeLinkedFrom(target.id).first().map { it.id },
        )
    }

    @Test
    fun backfillRelinksNotesWrittenBypassingRepository() = runBlocking {
        // Simulate pre-migration rows: written straight through the DAO,
        // so note_links stays empty although a [[reference]] exists.
        val now = System.currentTimeMillis()
        db.noteDao().upsert(
            NoteEntity(id = "t1", title = "Target", plainText = "body", createdAt = now, updatedAt = now),
        )
        db.noteDao().upsert(
            NoteEntity(id = "s1", title = "Source", plainText = "See [[Target]]", createdAt = now, updatedAt = now),
        )
        assertTrue(repository.observeLinkedFrom("t1").first().isEmpty())

        assertTrue(repository.backfillLinksIfNeeded())
        assertEquals(
            listOf("s1"),
            repository.observeLinkedFrom("t1").first().map { it.id },
        )
        // Idempotent: with edges present the backfill is a no-op.
        assertTrue(!repository.backfillLinksIfNeeded())
    }

    @Test
    fun backfillScansALinkFreeLibraryOnceEver() = runBlocking {
        // Performance chapter 2 (F7): a library with no links at
        // all pays the body scan once ever — the done-marker —
        // not once per launch as the original backfill did.
        val now = System.currentTimeMillis()
        db.noteDao().upsert(
            NoteEntity(id = "p1", title = "Plain", plainText = "no links here", createdAt = now, updatedAt = now),
        )
        db.noteDao().upsert(
            NoteEntity(id = "p2", title = "Also plain", plainText = "still none", createdAt = now, updatedAt = now),
        )
        assertTrue(repository.backfillLinksIfNeeded())
        assertTrue(!repository.backfillLinksIfNeeded())
        // A [[-bearing row that lands later (written straight
        // through the DAO, as a pre-1c row would) is not picked
        // up by the backfill any more: since Phase 1c every
        // repository write relinks its own note.
        db.noteDao().upsert(
            NoteEntity(id = "s9", title = "Late", plainText = "See [[Plain]]", createdAt = now, updatedAt = now),
        )
        assertTrue(!repository.backfillLinksIfNeeded())
        assertTrue(repository.observeLinkedFrom("p1").first().isEmpty())
    }

    @Test
    fun searchCorpusIsLoadedOnceUntilANoteChanges() = runBlocking {
        // Performance chapter 2 (F6): repeated searches on an
        // unchanged library perform ONE corpus load; a note edit
        // moves the library stamp and the next search reloads.
        val first = repository.createRichNote("Alpha", "<p>zebra apple orchard</p>")
        repository.createRichNote("Beta", "<p>zebra banana grove</p>")

        assertEquals(2, repository.searchNotes("zebra").size)
        assertEquals(1, repository.corpusLoadCount)

        assertEquals(listOf(first.id), repository.searchNotes("apple").map { it.id })
        assertEquals(1, repository.corpusLoadCount)

        val stored = db.noteDao().getById(first.id)!!
        repository.updateRichBody(stored, "Alpha", "<p>zebra cherry orchard</p>")
        assertEquals(listOf(first.id), repository.searchNotes("cherry").map { it.id })
        assertEquals(2, repository.corpusLoadCount)
    }

    @Test
    fun stressLibrarySearchMatchesBruteForceReference() = runBlocking {
        // Performance chapter 2 (F6): at stress scale (2,000
        // seeded notes), the cached-corpus search returns EXACTLY
        // what a brute-force reference scorer computes over the
        // raw corpus — same ids, same order, same scores — and
        // the whole battery costs one corpus load.
        StressLibrary.seed(db)
        val queries = listOf("river", "saffron", "stress note 199", "topic 3", "harvest")
        for (query in queries) {
            val expected = bruteForceSearch(query)
            val actual = repository.searchNotes(query)
            assertEquals("id order for '$query'", expected.map { it.first }, actual.map { it.id })
            assertEquals("scores for '$query'", expected.map { it.second }, actual.map { it.score })
        }
        assertEquals(1, repository.corpusLoadCount)
    }

    /** The search pipeline with no cache: score every corpus row
     *  with the production scorer and sort by the production
     *  order (score, then recency, then id). The reference the
     *  cached path must match exactly. */
    private suspend fun bruteForceSearch(raw: String): List<Pair<String, Double>> {
        val terms = NoteSearch.terms(raw)
        if (terms.isEmpty()) return emptyList()
        val texts = db.noteDao().getSearchTexts()
        val textById = texts.associateBy { it.id }
        val tagNamesById = HashMap<String, List<String>>()
        for (chunk in textById.keys.chunked(500)) {
            for ((noteId, names) in db.tagDao().getTagNamesForNotes(chunk)
                .groupBy({ it.noteId }, { it.tagName })) {
                tagNamesById[noteId] = names
            }
        }
        val folderNamesById = db.folderDao().getAll().associate { it.id to it.name }
        val rowsById = HashMap<String, app.nota.data.db.NoteListRow>()
        for (chunk in textById.keys.chunked(500)) {
            for (row in db.noteDao().getRowsByIds(chunk)) rowsById[row.id] = row
        }
        val scored = ArrayList<Triple<String, Double, Long>>()
        for ((id, text) in textById) {
            val row = rowsById[id] ?: continue
            val score = NoteSearch.score(
                title = text.title,
                body = if (text.locked) "" else text.plainText,
                tagNames = tagNamesById[id] ?: emptyList(),
                folderName = row.folderId?.let { folderNamesById[it] },
                terms = terms,
            ) ?: continue
            scored += Triple(id, score.toDouble(), row.updatedAt)
        }
        return scored
            .sortedWith(
                compareByDescending<Triple<String, Double, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first },
            )
            .map { it.first to it.second }
    }
}
