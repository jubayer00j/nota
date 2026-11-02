package app.nota.data.backup

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.NotaDatabase
import app.nota.data.html.NoteDocument
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TextSegment
import app.nota.data.templates.TemplateRepository
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 2.5 fix F1 regression: a richly formatted 1.x note restored
 * from a REAL backup lost its bullet/numbered lists — the notes
 * "arrived as raw text" on device.
 *
 * The fixture below is built from the REAL 1.x writer's layout, not
 * from the importer's assumptions: entry order and pretty-printing
 * copy `buildBackupZip` (noma `backup.ts`), the note JSON's field
 * order copies `createNote` (noma `notes.ts`), `settings.json` is the
 * sanitised settings OBJECT a real backup carries (not the literal
 * `null` the PR-C fixture used), and the note body is the HTML
 * dialect Tiptap actually emits — headings, bold/italic, a styled
 * highlight, bullet + numbered lists whose items wrap their content
 * in `<p>` (`<li><p>…</p></li>`, the shape in noma's own
 * `notes.test.ts` / `sanitize.test.ts`), a link carrying
 * target/rel, a colour span, and an image paragraph.
 *
 * The importer stores that HTML (test 1; its single mapping is the
 * inline image's src scheme, normalised to Nota's own); the
 * loss happened one step later, when the restored note was opened:
 * the editor hands each text segment to RichTextState.setHtml, whose
 * parser cannot represent a `<p>` inside an `<li>` and drops the
 * list, and the 1.5 s autosave then persisted the flattened body.
 * Test 2 replays that exact load → autosave transform and asserts
 * the lists survive it (the NoteDocument guard now normalises the
 * Tiptap list dialect to the bare-item shape the library
 * round-trips). Test 3 covers the export → restore round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRichFormattingRestoreTest {

    private val richHtml =
        "<h2>Exam Plan</h2>" +
            "<p>Read <strong>Physics</strong> and <em>Biology</em> " +
            "<mark style=\"background-color: #fff3a3\">tonight</mark>.</p>" +
            "<ul><li><p>Chapter 4</p></li>" +
            "<li><p>Chapter 5</p><ul><li><p>Section 5.1</p></li></ul></li></ul>" +
            "<ol start=\"3\"><li><p>Third</p></li><li><p>Fourth</p></li></ol>" +
            "<p>See <a href=\"https://example.com/ref\" target=\"_blank\" " +
            "rel=\"noopener noreferrer nofollow\">the reference</a> and " +
            "<span style=\"color: #c0392b\">red words</span>.</p>" +
            "<p><img src=\"noma-attachment://att-img-1\" alt=\"diagram\" width=\"50%\"></p>"

    /**
     * [richHtml] as it is stored after a restore (Phase 2.5, F1
     * companion): identical byte-for-byte EXCEPT the inline image's
     * src scheme, which the importer normalises from 1.x's
     * `noma-attachment://` to Nota's `nota-attachment://` so the
     * editor resolves the image. Export maps it back, so the
     * exported JSON carries [richHtml] again exactly.
     */
    private val storedHtml = richHtml.replace(
        "src=\"noma-attachment://",
        "src=\"nota-attachment://",
    )

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAwMCAO+ip1sAAAAASUVORK5CYII=",
    )

    private val dbs = mutableListOf<NotaDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { it.close() }
    }

    private inner class Stack(val db: NotaDatabase) {
        val context = RuntimeEnvironment.getApplication()
        val store = AttachmentStore(context, db.attachmentDao())
        val backup = BackupRepository(context, db, TemplateRepository(context), store)
    }

    private fun newStack(): Stack {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries().build()
        dbs += db
        return Stack(db)
    }

    private fun esc(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The real-writer fixture ZIP (entries in buildBackupZip order,
     *  JSON pretty-printed with 2-space indent like JSON.stringify). */
    private fun realWriterZip(): File {
        val file = File.createTempFile("rich-fixture", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun text(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            fun bytes(name: String, body: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
            }
            text(
                "manifest.json",
                """{
  "backupFormatVersion": 2,
  "ownerId": null,
  "libraryId": "nota_guest",
  "nomaVersion": "1.3.0",
  "createdAt": "2026-11-01T00:00:00.000Z",
  "noteCount": 1,
  "folderCount": 1,
  "tagCount": 1,
  "attachmentCount": 1,
  "reminderCount": 0,
  "deviceId": null
}""",
            )
            text("folders.json", """[
  {
    "id": "folder-1",
    "name": "Study",
    "parentId": null,
    "createdAt": 1730000000000,
    "updatedAt": 1730000000000
  }
]""")
            text("tags.json", """[
  {
    "id": "tag-1",
    "name": "exam",
    "createdAt": 1730000000000
  }
]""")
            text("reminders.json", "[]")
            // A real backup carries the sanitised settings OBJECT
            // (collectBackup nulls the credential fields); the reader
            // must drain it, never import it.
            text("settings.json", """{
  "id": "app",
  "theme": "dark",
  "fontSize": 17,
  "passwordHash": null,
  "passwordSalt": null,
  "patternHash": null,
  "patternSalt": null,
  "failedAttempts": 0,
  "lockoutUntil": null,
  "appLockEnabled": false,
  "unlockMethods": {
    "biometric": false,
    "pattern": false,
    "password": false
  }
}""")
            text("sync.json", """{
  "deviceId": null,
  "syncedAt": "2026-11-01T00:00:00.000Z",
  "tombstones": []
}""")
            // JSZip emits explicit folder entries ahead of their files.
            zip.putNextEntry(ZipEntry("notes/")); zip.closeEntry()
            text(
                "notes/note-rich-1.json",
                """{
  "id": "note-rich-1",
  "title": "Rich Fixture",
  "content": "${esc(richHtml)}",
  "contentFormat": "tiptap-html",
  "createdAt": 1730000000000,
  "updatedAt": 1730000001000,
  "folderId": "folder-1",
  "tagIds": [
    "tag-1"
  ],
  "pinned": false,
  "favorite": true,
  "archived": false,
  "deleted": false,
  "deletedAt": null,
  "reminderAt": null,
  "wordCount": 16
}""",
            )
            val pngDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
            zip.putNextEntry(ZipEntry("attachments/")); zip.closeEntry()
            text(
                "attachments/index.json",
                """[
  {
    "id": "att-img-1",
    "noteId": "note-rich-1",
    "name": "photo.png",
    "mimeType": "image/png",
    "size": ${pngBytes.size},
    "createdAt": 1730000000000
  }
]""",
            )
            bytes("attachments/att-img-1-photo.png", pngBytes)
            text(
                "attachments/att-img-1.json",
                """{
  "id": "att-img-1",
  "noteId": "note-rich-1",
  "name": "photo.png",
  "mimeType": "image/png",
  "size": ${pngBytes.size},
  "createdAt": 1730000000000,
  "data": "$pngDataUrl"
}""",
            )
        }
        return file
    }

    /**
     * Replays the editor's exact load → autosave transform
     * (RichNoteEditorScreen.richStateFor / syncSegments +
     * RichNoteEditorViewModel.persist): parse with the NoteDocument
     * guard, round-trip every text/quote segment through
     * RichTextState.setHtml → toHtml, serialise back.
     */
    private fun editorRoundTrip(html: String): String {
        val synced = NoteDocument.parse(html).map { segment ->
            when (segment) {
                is TextSegment -> TextSegment(
                    RichTextState().apply { setHtml(segment.html) }.toHtml(),
                )
                is QuoteSegment -> QuoteSegment(
                    RichTextState().apply { setHtml(segment.innerHtml) }.toHtml(),
                )
                else -> segment
            }
        }
        return NoteDocument.serialize(synced)
    }

    private fun assertFormattingSurvives(html: String, stage: String) {
        assertTrue("$stage: bullet list lost:\n$html", html.contains("<ul>"))
        assertTrue("$stage: bullet item lost:\n$html", html.contains("<li>Chapter 4</li>"))
        assertTrue("$stage: numbered list lost:\n$html", html.contains("<ol start=\"3\">"))
        assertTrue("$stage: numbered item lost:\n$html", html.contains("<li>Third</li>"))
        assertFalse(
            "$stage: list item flattened to a paragraph:\n$html",
            html.contains("<p>Chapter 4</p>"),
        )
        assertTrue("$stage: heading lost:\n$html", html.contains("<h2>"))
        assertTrue("$stage: link lost:\n$html", html.contains("https://example.com/ref"))
    }

    @Test
    fun realWriterFixture_restoreKeepsHtmlVerbatim() = runBlocking {
        val stack = newStack()
        stack.backup.restore(realWriterZip(), null, RestoreMode.REPLACE)

        val note = stack.db.noteDao().getById("note-rich-1")!!
        // The importer mapping stores the backup's HTML as contentHtml
        // and derives plainText FROM it — byte-for-byte, with the one
        // documented image-scheme normalisation (see storedHtml).
        assertEquals(storedHtml, note.contentHtml)
        assertTrue(note.plainText.contains("Exam Plan"))
        assertTrue(note.plainText.contains("Chapter 4"))
        assertTrue(note.plainText.contains("Third"))

        val imgRow = stack.db.attachmentDao().getForNote("note-rich-1").single()
        assertArrayEquals(pngBytes, stack.store.fileFor(imgRow).readBytes())
    }

    @Test
    fun restoredNote_survivesEditorLoadAutosaveRoundTrip() = runBlocking {
        val stack = newStack()
        stack.backup.restore(realWriterZip(), null, RestoreMode.REPLACE)
        val note = stack.db.noteDao().getById("note-rich-1")!!

        // Opening the restored note and letting the autosave fire must
        // not flatten it (this is the transform that destroyed the
        // lists on device before the guard fix).
        assertFormattingSurvives(editorRoundTrip(note.contentHtml), "editor round trip")
    }

    @Test
    fun exportRestoreRoundTrip_preservesRichHtml() = runBlocking {
        val stack = newStack()
        stack.backup.restore(realWriterZip(), null, RestoreMode.REPLACE)

        val exported = File.createTempFile("rich-export", ".zip")
        stack.backup.exportToFile(exported, null)
        ZipFile(exported).use { zip ->
            val entry = zip.getEntry("notes/note-rich-1.json")
            val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            assertEquals(richHtml, JSONObject(text).getString("content"))
        }

        val stack2 = newStack()
        stack2.backup.restore(exported, null, RestoreMode.REPLACE)
        val restored = stack2.db.noteDao().getById("note-rich-1")!!
        assertEquals(storedHtml, restored.contentHtml)
        assertFormattingSurvives(editorRoundTrip(restored.contentHtml), "re-restored editor round trip")
    }
}
