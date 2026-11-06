package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Phase 6.1: the lightweight list-row projections the notes list,
 * Today and search now render from. Pins the contract the diet rests
 * on: rows carry the fields those surfaces read (title, SQL-cut
 * excerpt, dates, flags), slices split exactly like the full-entity
 * queries did, a locked note's excerpt is empty by construction, and
 * the id-only change signal the background triggers observe still
 * pulses on a body-only edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteListRowsRobolectricTest {

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

    private fun entity(
        id: String,
        title: String,
        plainText: String,
        updatedAt: Long,
        pinned: Boolean = false,
        archived: Boolean = false,
        trashedAt: Long? = null,
        locked: Boolean = false,
    ) = NoteEntity(
        id = id,
        title = title,
        contentHtml = "<p>$plainText</p>",
        plainText = plainText,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        pinned = pinned,
        archived = archived,
        trashedAt = trashedAt,
        locked = locked,
        lockRecordJson = if (locked) "{}" else null,
    )

    @Test
    fun activeRowsCarrySnippetFieldsAndSqlCutExcerpt() = runBlocking {
        val longText = "x".repeat(500)
        db.noteDao().upsert(entity("a", "Alpha", longText, updatedAt = 100))
        db.noteDao().upsert(entity("b", "Beta", "short body", updatedAt = 300, pinned = true))
        db.noteDao().upsert(entity("c", "Gamma", "mid body", updatedAt = 200))

        val rows = repository.observeActiveRows().first()
        // Same order as observeActive: pinned first, then updatedAt desc.
        assertEquals(listOf("b", "c", "a"), rows.map { it.id })
        val alpha = rows.first { it.id == "a" }
        // The excerpt is cut in SQL at 240 chars — the full 500-char
        // text never crosses into the row.
        assertEquals(240, alpha.excerpt.length)
        assertEquals(longText.take(240), alpha.excerpt)
        assertEquals("short body", rows.first { it.id == "b" }.excerpt)
        assertEquals("Alpha", alpha.title)
        assertTrue(rows.first { it.id == "b" }.pinned)
    }

    @Test
    fun slicesSplitLikeTheEntityQueries() = runBlocking {
        db.noteDao().upsert(entity("live", "Live", "body", updatedAt = 10))
        db.noteDao().upsert(entity("arch", "Archived", "body", updatedAt = 20, archived = true))
        db.noteDao().upsert(entity("trash", "Trashed", "body", updatedAt = 30, trashedAt = 40))

        assertEquals(listOf("live"), repository.observeActiveRows().first().map { it.id })
        assertEquals(listOf("arch"), repository.observeArchivedRows().first().map { it.id })
        assertEquals(listOf("trash"), repository.observeTrashedRows().first().map { it.id })
    }

    @Test
    fun lockedRowExcerptIsEmptyByConstruction() = runBlocking {
        // A locked row as the lock flow leaves it: plainText already
        // empty under the seal. The projection must surface exactly
        // that — title visible, excerpt empty, lock flag set.
        db.noteDao().upsert(
            entity("locked", "Secret", "", updatedAt = 10, locked = true),
        )
        val row = repository.observeActiveRows().first().single()
        assertEquals("Secret", row.title)
        assertEquals("", row.excerpt)
        assertTrue(row.locked)
    }

    @Test
    fun getRowByIdAndFtsRowsMatchTheListShape() = runBlocking {
        val note = repository.createNote(title = "Alpha", plainBody = "zebra apple")
        val row = repository.getRowById(note.id)
        assertEquals("Alpha", row?.title)
        assertEquals("zebra apple", row?.excerpt)

        val hits = repository.searchNotes("zebra")
        assertEquals(listOf(note.id), hits.map { it.id })
        assertEquals("zebra apple", hits.single().excerpt)
    }

    @Test
    fun activeIdsSignalPulsesOnBodyOnlyEdit() = runBlocking {
        val note = repository.createNote(title = "Alpha", plainBody = "one")
        val emissions = async {
            val seen = mutableListOf<List<String>>()
            withTimeout(10_000) {
                repository.observeActiveIds().collect { ids ->
                    seen += ids
                    if (seen.size >= 2) return@withTimeout seen
                }
            }
            seen
        }
        // Let the collector subscribe before the write lands.
        kotlinx.coroutines.delay(300)
        repository.updateBody(note, "Alpha", "two")
        val seen = emissions.await()
        assertTrue(seen.size >= 2)
        assertEquals(listOf(note.id), seen.last())
    }
}
