package app.nota.data.db

import androidx.room.Room
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Search Quality Pass (v0.27.0): MIGRATION_7_8 widens notes_fts
 * with `tagsText` + `folderName` and REBUILDS it from the live
 * library in the same pass. The library is seeded through the
 * real repositories, notes_fts is then rolled back to its exact
 * v7 shape (the 7.json createSql) and EMPTIED — the state an
 * upgrading install's data must survive — and the REAL
 * migration object runs: the new columns must exist, and the
 * rebuilt rows must carry each note's tag names and folder
 * name, a locked note's body must stay blanked, and a trashed
 * note must get no row at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration78RobolectricTest {

    private lateinit var db: NotaDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private data class FtsRow(
        val title: String,
        val plainText: String,
        val tagsText: String,
        val folderName: String,
    )

    private fun ftsRow(noteId: String): FtsRow? {
        val cursor = db.query(
            "SELECT title, plainText, tagsText, folderName FROM notes_fts WHERE noteId = ?",
            arrayOf<Any>(noteId),
        )
        return cursor.use {
            if (!it.moveToFirst()) null
            else FtsRow(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        }
    }

    private fun ftsColumns(): List<String> {
        val cursor = db.openHelper.writableDatabase
            .query("PRAGMA table_info(`notes_fts`)")
        return cursor.use {
            val out = ArrayList<String>()
            val nameIdx = it.getColumnIndex("name")
            while (it.moveToNext()) out += it.getString(nameIdx)
            out
        }
    }

    @Test
    fun migrationWidensFtsAndRebuildsItFromTheLibrary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = DatabaseProvider.forDatabase(context, db)
        val notes = NoteRepository(provider)
        val library = LibraryRepository(provider)

        val folder = library.createFolder("Recipes")
        val open = notes.createNote(
            title = "Pasta", plainBody = "boil the pasta water", folderId = folder.id,
        )
        notes.setNoteTags(open.id, listOf("italian"))
        val locked = notes.createNote(title = "Vault codes", plainBody = "the secret code")
        val storedLocked = db.noteDao().getById(locked.id)!!
        notes.upsert(storedLocked.copy(locked = true, plainText = "", contentHtml = "sealed"))
        val trashed = notes.createNote(title = "Old draft", plainBody = "pasta again")
        notes.trash(db.noteDao().getById(trashed.id)!!)

        // Roll notes_fts back to the v7 shape and empty it: the
        // migration — not any pre-existing row — must produce
        // the rebuilt index.
        val supportDb = db.openHelper.writableDatabase
        supportDb.execSQL("DROP TABLE IF EXISTS `notes_fts`")
        supportDb.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(" +
                "`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, `plainText` TEXT NOT NULL)",
        )

        MIGRATION_7_8.migrate(supportDb)

        assertEquals(
            listOf("noteId", "title", "plainText", "tagsText", "folderName"),
            ftsColumns(),
        )

        val openRow = ftsRow(open.id)!!
        assertEquals("Pasta", openRow.title)
        assertEquals("boil the pasta water", openRow.plainText)
        assertEquals("italian", openRow.tagsText)
        assertEquals("Recipes", openRow.folderName)

        // The seal survives the rebuild: no body text, title kept.
        val lockedRow = ftsRow(locked.id)!!
        assertEquals("Vault codes", lockedRow.title)
        assertEquals("", lockedRow.plainText)

        // Trashed notes keep no FTS row (the standing rule).
        assertNull(ftsRow(trashed.id))

        // The rebuilt index answers MATCH on a tag name.
        val cursor = db.query(
            "SELECT noteId FROM notes_fts WHERE notes_fts MATCH ?",
            arrayOf<Any>("\"italian\"*"),
        )
        val matched = cursor.use {
            val out = ArrayList<String>()
            while (it.moveToNext()) out += it.getString(0)
            out
        }
        assertEquals(listOf(open.id), matched)
    }

    /**
     * Performance chapter 2 R3: MIGRATION_8_9 installs the
     * library write-generation machinery (`library_meta` + the
     * notes triggers) and preserves the library. (The pin lives
     * in this class — the repo's migration-test home — because
     * CI enumerates test classes; a new class would silently
     * never run.) The database is rolled back to its exact v8
     * state (Room built it at v9, so the entity table goes; the
     * triggers were never Room's to create) and the REAL
     * migration object runs. The stamp contract is then proven
     * at the DAO: a content swap at identical lengths with an
     * unchanged updatedAt — the sync-tie shape the aggregates
     * cannot see — moves ONLY the generation, inserts and
     * deletes move it one write at a time, and pure reads never
     * move it at all.
     */
    @Test
    fun migration89InstallsWriteGenerationAndPreservesLibrary() = runBlocking {
        val dao = db.noteDao()
        val alpha = NoteEntity(
            id = "mig-a", title = "aaaa", contentHtml = "<p>xxxx</p>",
            plainText = "xxxx", createdAt = 1000, updatedAt = 5000,
        )
        val beta = NoteEntity(
            id = "mig-b", title = "bb", contentHtml = "<p>yy</p>",
            plainText = "yy", createdAt = 1000, updatedAt = 7000,
        )
        dao.upsert(alpha)
        dao.upsert(beta)

        // Roll back to the v8 shape: no counter table, no triggers.
        val supportDb = db.openHelper.writableDatabase
        supportDb.execSQL("DROP TABLE IF EXISTS `library_meta`")
        supportDb.execSQL("DROP TRIGGER IF EXISTS `trg_library_meta_notes_insert`")
        supportDb.execSQL("DROP TRIGGER IF EXISTS `trg_library_meta_notes_update`")
        supportDb.execSQL("DROP TRIGGER IF EXISTS `trg_library_meta_notes_delete`")

        MIGRATION_8_9.migrate(supportDb)

        // The library survives untouched.
        assertEquals(alpha, dao.getById("mig-a"))
        assertEquals(beta, dao.getById("mig-b"))

        // The triggers landed in the schema.
        val triggerCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name",
            emptyArray<Any>(),
        )
        val triggers = triggerCursor.use {
            val out = ArrayList<String>()
            while (it.moveToNext()) out += it.getString(0)
            out
        }
        assertEquals(
            listOf(
                "trg_library_meta_notes_delete",
                "trg_library_meta_notes_insert",
                "trg_library_meta_notes_update",
            ),
            triggers,
        )

        val s0 = dao.getLibraryStampRow()
        assertEquals(2L, s0.noteCount)
        assertEquals(0L, s0.writeGeneration)

        // The sync-tie write: same lengths, same updatedAt,
        // different text. Every aggregate holds; only the
        // generation may move — and it must.
        dao.upsert(alpha.copy(title = "cccc", plainText = "zzzz"))
        val s1 = dao.getLibraryStampRow()
        assertTrue(s1.writeGeneration > s0.writeGeneration)
        assertEquals(s0.copy(writeGeneration = s1.writeGeneration), s1)

        // Insert and delete move the counter one write each.
        val gamma = NoteEntity(
            id = "mig-c", title = "g", contentHtml = "<p>g</p>",
            plainText = "g", createdAt = 1000, updatedAt = 9000,
        )
        dao.upsert(gamma)
        val s2 = dao.getLibraryStampRow()
        assertEquals(s1.writeGeneration + 1, s2.writeGeneration)
        dao.delete(gamma)
        val s3 = dao.getLibraryStampRow()
        assertEquals(s2.writeGeneration + 1, s3.writeGeneration)

        // Pure reads never move it.
        dao.getById("mig-a")
        dao.getAll()
        assertEquals(s3, dao.getLibraryStampRow())
    }
}
