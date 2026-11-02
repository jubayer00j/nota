package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.db.ReminderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 2.9 (J3) reproduction + regression: swipe-archive and
 * swipe-trash followed by an INSTANT Undo must return the note to its
 * pre-swipe state EXACTLY — every note field, its tag links, its
 * attachment rows, its reminder rows, its FTS projection and its
 * backlink edges.
 *
 * The sequence mirrors NotesScreen.swipeWithUndo precisely: the swipe
 * runs the state op ([NoteRepository.setArchived] /
 * [NoteRepository.trash] / [NoteRepository.restore]) with the entity
 * snapshot the list was showing, and Undo re-upserts THAT SAME
 * pre-action snapshot ([NotesViewModel.upsert] → repository.upsert).
 * Any divergence between the before/after snapshots is dumped in
 * full so a failure names the exact component that did not come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwipeUndoFidelityRobolectricTest {

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

    /** Everything the diff covers, as comparable strings. */
    private data class FullState(
        val note: NoteEntity?,
        val tagIds: List<String>,
        val attachments: List<AttachmentEntity>,
        val reminders: List<ReminderEntity>,
        val ftsRows: List<String>,
        val linksOut: List<String>,
        val linksIn: List<String>,
    )

    private fun queryStrings(sql: String, arg: String): List<String> {
        val out = mutableListOf<String>()
        db.query(sql, arrayOf<Any>(arg)).use { c ->
            while (c.moveToNext()) {
                out += (0 until c.columnCount).joinToString("|") { c.getString(it) ?: "NULL" }
            }
        }
        return out.sorted()
    }

    private suspend fun snapshot(noteId: String): FullState {
        val crossRefs = mutableListOf<String>()
        db.query("SELECT tagId FROM note_tags WHERE noteId = ?", arrayOf<Any>(noteId)).use { c ->
            while (c.moveToNext()) crossRefs += c.getString(0)
        }
        val attachments = mutableListOf<AttachmentEntity>()
        db.query(
            "SELECT id, noteId, fileName, mimeType, sizeBytes, localPath, widthPx, heightPx, createdAt, encrypted " +
                "FROM attachments WHERE noteId = ? ORDER BY id",
            arrayOf<Any>(noteId),
        ).use { c ->
            while (c.moveToNext()) {
                attachments += AttachmentEntity(
                    id = c.getString(0), noteId = c.getString(1), fileName = c.getString(2),
                    mimeType = c.getString(3), sizeBytes = c.getLong(4), localPath = c.getString(5),
                    widthPx = if (c.isNull(6)) null else c.getInt(6),
                    heightPx = if (c.isNull(7)) null else c.getInt(7),
                    createdAt = c.getLong(8), encrypted = c.getInt(9) != 0,
                )
            }
        }
        val reminders = mutableListOf<ReminderEntity>()
        db.query(
            "SELECT id, noteId, triggerAt, repeatRule, enabled, createdAt FROM reminders WHERE noteId = ? ORDER BY id",
            arrayOf<Any>(noteId),
        ).use { c ->
            while (c.moveToNext()) {
                reminders += ReminderEntity(
                    id = c.getString(0), noteId = c.getString(1), triggerAt = c.getLong(2),
                    repeatRule = if (c.isNull(3)) null else c.getString(3),
                    enabled = c.getInt(4) != 0, createdAt = c.getLong(5),
                )
            }
        }
        return FullState(
            note = db.noteDao().getById(noteId),
            tagIds = crossRefs.sorted(),
            attachments = attachments,
            reminders = reminders,
            ftsRows = queryStrings(
                "SELECT noteId, title, plainText FROM notes_fts WHERE noteId = ?", noteId,
            ),
            linksOut = queryStrings(
                "SELECT sourceId, targetId FROM note_links WHERE sourceId = ?", noteId,
            ),
            linksIn = queryStrings(
                "SELECT sourceId, targetId FROM note_links WHERE targetId = ?", noteId,
            ),
        )
    }

    private fun diff(before: FullState, after: FullState): String {
        val sb = StringBuilder()
        if (before.note != after.note) {
            sb.appendLine("NOTE ROW differs:")
            sb.appendLine("  before: ${before.note}")
            sb.appendLine("  after:  ${after.note}")
        }
        if (before.tagIds != after.tagIds) {
            sb.appendLine("TAG LINKS differ: before=${before.tagIds} after=${after.tagIds}")
        }
        if (before.attachments != after.attachments) {
            sb.appendLine("ATTACHMENTS differ:\n  before=${before.attachments}\n  after=${after.attachments}")
        }
        if (before.reminders != after.reminders) {
            sb.appendLine("REMINDERS differ:\n  before=${before.reminders}\n  after=${after.reminders}")
        }
        if (before.ftsRows != after.ftsRows) {
            sb.appendLine("FTS ROWS differ:\n  before=${before.ftsRows}\n  after=${after.ftsRows}")
        }
        if (before.linksOut != after.linksOut) {
            sb.appendLine("LINKS OUT differ: before=${before.linksOut} after=${after.linksOut}")
        }
        if (before.linksIn != after.linksIn) {
            sb.appendLine("LINKS IN differ: before=${before.linksIn} after=${after.linksIn}")
        }
        return sb.toString()
    }

    private suspend fun richFixture(): NoteEntity {
        val folder = repository.let {
            val f = app.nota.data.db.FolderEntity(
                id = "folder-1", name = "Work", colorArgb = 123, createdAt = 1000L, updatedAt = 1000L,
            )
            db.folderDao().upsert(f)
            f
        }
        repository.createNote(title = "Project Plan", plainBody = "roadmap body")
        val note = repository.createRichNote(
            title = "Meeting Notes",
            contentHtml = "<p>Discuss <b>budget</b> — see [[Project Plan]]</p>",
            folderId = folder.id,
        )
        // Enrich: pin, favourite, colour, tags, attachment, reminder.
        val enriched = note.copy(pinned = true, favourite = true, colorArgb = 0xFF336699.toInt())
        repository.upsert(enriched)
        repository.setNoteTags(note.id, listOf("alpha", "beta"))
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-1", noteId = note.id, fileName = "photo.jpg",
                mimeType = "image/jpeg", sizeBytes = 4242L,
                localPath = "attachments/${note.id}/photo.jpg",
                widthPx = 800, heightPx = 600, createdAt = 2000L,
            ),
        )
        db.reminderDao().upsert(
            ReminderEntity(
                id = "rem-1", noteId = note.id, triggerAt = 999_000L,
                repeatRule = null, enabled = true, createdAt = 2000L,
            ),
        )
        return db.noteDao().getById(note.id)!!
    }

    @Test
    fun swipeArchiveThenInstantUndoRestoresFullState() = runBlocking {
        val note = richFixture()
        val before = snapshot(note.id)

        // The exact NotesScreen sequence: state op with the list
        // snapshot, then Undo = upsert of that same snapshot.
        repository.setArchived(note, true)
        repository.upsert(note)

        val after = snapshot(note.id)
        val d = diff(before, after)
        if (d.isNotEmpty()) fail("Archive → Undo did not restore full state:\n$d")
    }

    @Test
    fun swipeTrashThenInstantUndoRestoresFullState() = runBlocking {
        val note = richFixture()
        val before = snapshot(note.id)

        repository.trash(note)
        repository.upsert(note)

        val after = snapshot(note.id)
        val d = diff(before, after)
        if (d.isNotEmpty()) fail("Trash → Undo did not restore full state:\n$d")
    }

    @Test
    fun swipeRestoreThenInstantUndoRestoresFullState() = runBlocking {
        val base = richFixture()
        repository.trash(base)
        val trashed = db.noteDao().getById(base.id)!!
        val before = snapshot(base.id)

        // In Trash, StartToEnd = restore; Undo = upsert of the
        // trashed snapshot the list was showing.
        repository.restore(trashed)
        repository.upsert(trashed)

        val after = snapshot(base.id)
        val d = diff(before, after)
        if (d.isNotEmpty()) fail("Restore → Undo did not restore full state:\n$d")
    }
}
