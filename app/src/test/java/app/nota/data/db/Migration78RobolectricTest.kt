package app.nota.data.db

import androidx.room.Room
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
