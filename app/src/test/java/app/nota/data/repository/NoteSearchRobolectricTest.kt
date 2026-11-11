package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Search Quality Pass (v0.27.0) against the real repository +
 * in-memory Room: relevance ranking over the FTS candidates
 * (G1), the substring fallback for mid-word fragments (G3),
 * tag/folder searchability kept fresh through renames and
 * deletes (G4), and the locked-note discipline (title, tags
 * and folder searchable; sealed body never, no snippet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteSearchRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository
    private lateinit var library: LibraryRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
        library = LibraryRepository(provider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun pinUpdatedAt(noteId: String, updatedAt: Long) {
        val note = db.noteDao().getById(noteId)!!
        repository.upsert(note.copy(updatedAt = updatedAt))
    }

    @Test
    fun rankingPutsTheTitleHitAboveTheNewerBodyHit() = runBlocking {
        val titleHit = repository.createNote(title = "Exam plan", plainBody = "nothing here")
        val bodyHit = repository.createNote(title = "Shopping", plainBody = "exam supplies")
        pinUpdatedAt(titleHit.id, 1_000)
        pinUpdatedAt(bodyHit.id, 2_000)

        val results = repository.searchNotes("exam")
        // 1.x's order: score first (title 6 > body 3), recency
        // only breaks ties — the older title hit wins.
        assertEquals(listOf(titleHit.id, bodyHit.id), results.map { it.id })
        assertEquals(6.0, results[0].score, 0.0)
        assertEquals(3.0, results[1].score, 0.0)
        // The body hit's snippet windows on the match, not the
        // note's head.
        assertTrue(results[1].snippet.contains("exam supplies"))
    }

    @Test
    fun rankingBreaksScoreTiesByRecency() = runBlocking {
        val older = repository.createNote(title = "One", plainBody = "zebra")
        val newer = repository.createNote(title = "Two", plainBody = "zebra")
        pinUpdatedAt(older.id, 1_500)
        pinUpdatedAt(newer.id, 2_500)

        assertEquals(
            listOf(newer.id, older.id),
            repository.searchNotes("zebra").map { it.id },
        )
    }

    @Test
    fun substringFallbackFindsMidWordFragmentsExactlyOnce() = runBlocking {
        val note = repository.createNote(title = "Studies", plainBody = "the examination board")

        // "xam" is a prefix of NOTHING in the note — the FTS
        // prefix pass alone returns no rows; the substring pass
        // must still find the note, once, through the same
        // ranked pipeline.
        val fallback = repository.searchNotes("xam")
        assertEquals(listOf(note.id), fallback.map { it.id })
        assertEquals(3.0, fallback.single().score, 0.0)
        assertTrue(fallback.single().snippet.contains("examination"))

        // And a term both passes can see still yields ONE row.
        assertEquals(listOf(note.id), repository.searchNotes("exam").map { it.id })
    }

    @Test
    fun tagsAndFolderNamesAreSearchableAndStayFresh() = runBlocking {
        val folder = library.createFolder("Recipes")
        val note = repository.createNote(
            title = "Pasta night", plainBody = "boil water", folderId = folder.id,
        )
        repository.setNoteTags(note.id, listOf("italian"))

        // Tag search (weight 2) and folder search (weight 1).
        val byTag = repository.searchNotes("italian")
        assertEquals(listOf(note.id), byTag.map { it.id })
        assertEquals(2.0, byTag.single().score, 0.0)
        val byFolder = repository.searchNotes("recipes")
        assertEquals(listOf(note.id), byFolder.map { it.id })
        assertEquals(1.0, byFolder.single().score, 0.0)

        // A folder rename re-indexes: the new name finds the
        // note, the old one stops matching.
        library.renameFolder(folder, "Cooking")
        assertEquals(listOf(note.id), repository.searchNotes("cooking").map { it.id })
        assertTrue(repository.searchNotes("recipes").isEmpty())

        // A tag rename does the same.
        val tag = db.tagDao().getAllTags().single()
        library.renameTag(tag, "sicilian")
        assertEquals(listOf(note.id), repository.searchNotes("sicilian").map { it.id })
        assertTrue(repository.searchNotes("italian").isEmpty())

        // Deleting the folder unfiles the note in the index too.
        library.deleteFolder(folder)
        assertTrue(repository.searchNotes("cooking").isEmpty())
    }

    @Test
    fun lockedNotesExposeTitleTagsAndFolderButNeverTheSealedBody() = runBlocking {
        val note = repository.createNote(
            title = "Secret exam", plainBody = "hidden answers inside",
        )
        repository.setNoteTags(note.id, listOf("vault"))
        // Seal it the way the lock leaves it stored: flagged
        // locked, plain text gone.
        val stored = db.noteDao().getById(note.id)!!
        repository.upsert(stored.copy(locked = true, plainText = "", contentHtml = "sealed"))

        // Sealed body words match nothing.
        assertTrue(repository.searchNotes("answers").isEmpty())
        assertTrue(repository.searchNotes("hidden").isEmpty())

        // The title still finds it — with NO snippet.
        val byTitle = repository.searchNotes("secret")
        assertEquals(listOf(note.id), byTitle.map { it.id })
        assertEquals("", byTitle.single().snippet)

        // And its tag does too: 1.x keeps a locked note's tags
        // (and folder) searchable — only the body is sealed.
        assertEquals(listOf(note.id), repository.searchNotes("vault").map { it.id })
    }

    @Test
    fun substringOnlyMatchSurvivesASaturatedPrefixPass() = runBlocking {
        // Saturate the FTS prefix pass: 30 notes whose bodies
        // hold a token STARTING with "exam"…
        repeat(30) { i ->
            repository.createNote(title = "Drill $i", plainBody = "examination practice $i")
        }
        // …and one note "exam" only ever touches mid-word.
        val midWord = repository.createNote(title = "Checklist", plainBody = "preexam checklist")

        val results = repository.searchNotes("exam")
        // 1.x scans the whole corpus unconditionally — the
        // mid-word note is a result however full the prefix
        // pass came back.
        assertEquals(31, results.size)
        assertTrue(results.any { it.id == midWord.id })
    }

    // ---- Bengali probe (G9) -----------------------------------
    // The same behaviours the English tests above pin, driven
    // through the real FTS + corpus pipeline with Bengali text:
    // the scored substring layer is what the user experiences,
    // whichever pass proposed the candidate.

    @Test
    fun bengaliTitleHitRanksAboveTheBodyHit() = runBlocking {
        val titleHit = repository.createNote(title = "ঢাকা ভ্রমণ", plainBody = "কিছু কথা")
        val bodyHit = repository.createNote(title = "নোট", plainBody = "আমার প্রিয় শহর ঢাকা")
        pinUpdatedAt(titleHit.id, 1_000)
        pinUpdatedAt(bodyHit.id, 2_000)

        val results = repository.searchNotes("ঢাকা")
        assertEquals(listOf(titleHit.id, bodyHit.id), results.map { it.id })
        assertEquals(6.0, results[0].score, 0.0)
        assertEquals(3.0, results[1].score, 0.0)
        assertTrue(results[1].snippet.contains("ঢাকা"))
    }

    @Test
    fun bengaliMidWordFragmentFindsTheNote() = runBlocking {
        val note = repository.createNote(
            title = "ঠিকানা", plainBody = "আমি বাংলাদেশ থেকে এসেছি",
        )
        // "লাদে" is a mid-word fragment of "বাংলাদেশ" — the
        // FTS prefix pass cannot see it; the substring corpus
        // pass must, exactly as for English fragments.
        val results = repository.searchNotes("লাদে")
        assertEquals(listOf(note.id), results.map { it.id })
        assertEquals(3.0, results.single().score, 0.0)
        assertTrue(results.single().snippet.contains("বাংলাদেশ"))
    }

    @Test
    fun bengaliTagAndFolderNamesAreSearchable() = runBlocking {
        val folder = library.createFolder("ভ্রমণ ডায়েরি")
        val note = repository.createNote(
            title = "Notes", plainBody = "plain english words", folderId = folder.id,
        )
        repository.setNoteTags(note.id, listOf("বাংলা"))

        val byTag = repository.searchNotes("বাংলা")
        assertEquals(listOf(note.id), byTag.map { it.id })
        assertEquals(2.0, byTag.single().score, 0.0)

        val byFolder = repository.searchNotes("ভ্রমণ")
        assertEquals(listOf(note.id), byFolder.map { it.id })
        assertEquals(1.0, byFolder.single().score, 0.0)
    }

    @Test
    fun bengaliTwoTermQueryRequiresBothTerms() = runBlocking {
        val both = repository.createNote(title = "ঢাকা", plainBody = "ভ্রমণ পরিকল্পনা")
        repository.createNote(title = "ঢাকা", plainBody = "অন্য কথা")

        assertEquals(
            listOf(both.id),
            repository.searchNotes("ঢাকা ভ্রমণ").map { it.id },
        )
    }

    @Test
    fun bengaliLockedNoteKeepsTitleAndTagsButNotItsBody() = runBlocking {
        val note = repository.createNote(
            title = "গোপন নোট", plainBody = "গোপনকথা এখানে আছে",
        )
        repository.setNoteTags(note.id, listOf("তালা"))
        val stored = db.noteDao().getById(note.id)!!
        repository.upsert(stored.copy(locked = true, plainText = "", contentHtml = "sealed"))

        // The sealed body's words match nothing…
        assertTrue(repository.searchNotes("গোপনকথা").isEmpty())
        // …the title still finds it, with no snippet…
        val byTitle = repository.searchNotes("গোপন")
        assertEquals(listOf(note.id), byTitle.map { it.id })
        assertEquals("", byTitle.single().snippet)
        // …and so does its tag, as in 1.x.
        assertEquals(listOf(note.id), repository.searchNotes("তালা").map { it.id })
    }

    @Test
    fun supersededSearchNeverReturnsResults() = runBlocking {
        repository.createNote(title = "Exam plan", plainBody = "exam body")
        // Cancelled before it can run: a superseded scan must
        // surface as a cancellation, never as a result list the
        // caller could publish for a stale query (Codex PR #48
        // R2 — the ViewModel cancels the previous search job on
        // every keystroke and relies on exactly this).
        val scan = async { repository.searchNotes("exam") }
        scan.cancel()
        try {
            scan.await()
            fail("a cancelled search must not complete with results")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // Expected: the scan aborted.
        }
    }
}
