package app.nota.data.importing

import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Keep Takeout parsing + the import engine end to end (Robolectric):
 * dedupe by (title, createdAt) in any state, trash mapping, labels →
 * tags, folders from Markdown paths, image embedding through the real
 * AttachmentStore pipeline, and the honest report counters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteImportRobolectricTest {

    private val dbs = mutableListOf<NotaDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { it.close() }
    }

    private inner class Stack(val db: NotaDatabase) {
        val context = RuntimeEnvironment.getApplication()
        val provider = DatabaseProvider.forDatabase(context, db)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
    }

    private var stackSeq = 0

    private fun newStack(): Stack {
        val context = RuntimeEnvironment.getApplication()
        val accountId = "import-${++stackSeq}"
        context.deleteDatabase(NotaDatabase.databaseName(accountId))
        val db = NotaDatabase.build(context, accountId)
        dbs += db
        return Stack(db)
    }

    private fun zipOf(
        entries: Map<String, String>,
        binaries: Map<String, ByteArray> = emptyMap(),
    ): File {
        val file = File.createTempFile("import-test", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for ((name, body) in binaries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun stagingDir(): File =
        java.nio.file.Files.createTempDirectory("import-parse").toFile()

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    private fun keepZip(): File = zipOf(
        linkedMapOf(
            "Takeout/Keep/shopping.json" to """{"title":"Shopping","textContent":"Milk\nEggs\n\nBread","isPinned":true,"labels":[{"name":"home"},{"name":"errands"}],"createdTimestampUsec":1700000000000000,"userEditedTimestampUsec":1700000060000000,"color":"GREEN"}""",
            "Takeout/Keep/tasks.json" to """{"title":"Tasks","listContent":[{"text":"One","isChecked":true},{"text":"Two\nlines","isChecked":false}],"createdTimestampUsec":1700000100000000}""",
            "Takeout/Keep/old.json" to """{"title":"Old","textContent":"gone","isTrashed":true,"createdTimestampUsec":1700000200000000,"userEditedTimestampUsec":1700000200000000}""",
            "Takeout/Keep/pic.json" to """{"title":"Pic","textContent":"look","attachments":[{"filePath":"Takeout/Keep/pic.png","mimetype":"image/png"}],"createdTimestampUsec":1700000300000000}""",
            "Takeout/Keep/voice.json" to """{"title":"Voice","attachments":[{"filePath":"Takeout/Keep/memo.m4a","mimetype":"audio/m4a"}],"createdTimestampUsec":1700000400000000}""",
            "Takeout/Keep/broken.json" to "{not json",
        ),
        mapOf(
            "Takeout/Keep/pic.png" to pngBytes,
            "Takeout/Keep/memo.m4a" to byteArrayOf(1, 2, 3),
        ),
    )

    @Test
    fun keepParseProducesNotesAndHonestCounters() {
        val result = KeepTakeoutParser.parse(keepZip(), stagingDir())
        assertEquals(5, result.notes.size)
        assertEquals(
            listOf(SkippedImportFile("broken.json", "Not valid JSON — skipped.")),
            result.skippedFiles,
        )
        assertEquals(1, result.attachmentsSkipped) // the audio memo
        assertEquals(1, result.coloredNotes)

        val shopping = result.notes.first { it.title == "Shopping" }
        assertEquals("<p>Milk<br>Eggs</p><p>Bread</p>", shopping.contentHtml)
        assertEquals(1_700_000_000_000L, shopping.createdAt)
        assertEquals(1_700_000_060_000L, shopping.updatedAt)
        assertTrue(shopping.pinned)
        assertEquals(listOf("home", "errands"), shopping.tagNames)
        assertNull(shopping.trashedAt)

        val tasks = result.notes.first { it.title == "Tasks" }
        assertEquals(
            "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>One</p></div></li>" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Two lines</p></div></li>" +
                "</ul>",
            tasks.contentHtml,
        )
        assertEquals(1_700_000_100_000L, tasks.updatedAt) // edited falls back to created

        val old = result.notes.first { it.title == "Old" }
        assertEquals(1_700_000_200_000L, old.trashedAt)

        val pic = result.notes.first { it.title == "Pic" }
        assertEquals(1, pic.images.size)
        assertNull(pic.images[0].token) // appended at the end
        assertEquals(pngBytes.size.toLong(), pic.images[0].sizeBytes)
    }

    @Test
    fun keepImportEndToEnd() = runBlocking {
        val stack = newStack()
        val outcome = stack.repo.importKeepZip(keepZip())
        assertEquals(5, outcome.report.imported)
        assertEquals(0, outcome.report.duplicates)
        assertEquals(0, outcome.report.failed)
        assertEquals(0, outcome.imageSaveFailures)

        val notes = stack.db.noteDao().getAll()
        assertEquals(5, notes.size)
        val shopping = notes.first { it.title == "Shopping" }
        assertTrue(shopping.pinned)
        assertNotNull(notes.first { it.title == "Old" }.trashedAt)

        // Labels became tags, linked to the note.
        val tagNames = stack.db.tagDao().getAllTags().map { it.name }.toSet()
        assertEquals(setOf("home", "errands"), tagNames)
        val crossRefs = stack.db.tagDao().observeAllCrossRefs().first()
        assertEquals(2, crossRefs.count { it.noteId == shopping.id })

        // The PNG rode the real attachment pipeline into an embed.
        val pic = notes.first { it.title == "Pic" }
        assertTrue(pic.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun keepReimportIsANoOp() = runBlocking {
        val stack = newStack()
        stack.repo.importKeepZip(keepZip())
        val second = stack.repo.importKeepZip(keepZip())
        assertEquals(0, second.report.imported)
        assertEquals(5, second.report.duplicates)
        assertEquals(5, stack.db.noteDao().getAll().size)
    }

    @Test
    fun trashedExistingNoteStillBlocksDuplicateImport() = runBlocking {
        val stack = newStack()
        stack.db.noteDao().upsert(
            NoteEntity(
                id = "pre-existing",
                title = "Shopping",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                trashedAt = 42L,
            ),
        )
        val outcome = stack.repo.importKeepZip(keepZip())
        assertEquals(4, outcome.report.imported)
        assertEquals(1, outcome.report.duplicates)
    }

    @Test
    fun keepGuardRejectsNonKeepJsonWhenNoKeepFolder() {
        val zip = zipOf(
            linkedMapOf(
                "random.json" to """{"foo":1}""",
                "real.json" to """{"title":"Real","textContent":"body","createdTimestampUsec":1700000000000000}""",
            ),
        )
        val result = KeepTakeoutParser.parse(zip, stagingDir())
        assertEquals(1, result.notes.size)
        assertEquals("Real", result.notes[0].title)
        assertEquals(
            listOf(SkippedImportFile("random.json", "Doesn't look like a Keep note.")),
            result.skippedFiles,
        )
    }

    @Test
    fun markdownZipImportCreatesFoldersTagsAndEmbeds() = runBlocking {
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf(
                "notes/hello.md" to "---\ntags: [x]\n---\n# Hello\n\nWorld **bold**",
                "notes/deep/inner.txt" to "plain body",
                "top.md" to "# Top\n\nbody",
                "notes/withpic.md" to "![p](pic.png)",
            ),
            mapOf("notes/pic.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownZip(zip)
        assertEquals(4, outcome.report.imported)
        assertEquals(0, outcome.report.failed)

        val notes = stack.db.noteDao().getAll()
        val foldersById = stack.db.folderDao().getAll().associateBy { it.id }
        assertEquals(setOf("notes", "deep"), foldersById.values.map { it.name }.toSet())
        assertEquals("notes", foldersById[notes.first { it.title == "Hello" }.folderId]?.name)
        assertEquals("deep", foldersById[notes.first { it.title == "inner" }.folderId]?.name)
        assertNull(notes.first { it.title == "Top" }.folderId)
        assertEquals(
            "<p>World <strong>bold</strong></p>",
            notes.first { it.title == "Hello" }.contentHtml,
        )
        assertEquals(setOf("x"), stack.db.tagDao().getAllTags().map { it.name }.toSet())
        assertTrue(
            notes.first { it.title == "withpic" }.contentHtml.contains("nota-attachment://"),
        )
    }

    @Test
    fun markdownSelectionReportsUnrecognisedFiles() = runBlocking {
        val stack = newStack()
        val dir = stagingDir()
        val md = File(dir, "a.md").apply { writeText("# A\n\nbody") }
        val txt = File(dir, "b.txt").apply { writeText("text body") }
        val pdf = File(dir, "c.pdf").apply { writeBytes(byteArrayOf(1, 2)) }
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.md", md, 1_700_000_000_000L),
                StagedImportFile("b.txt", txt, 1_700_000_000_000L),
                StagedImportFile("c.pdf", pdf, 1_700_000_000_000L),
            ),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(
            listOf(SkippedImportFile("c.pdf", "Not a Markdown, text or ZIP file.")),
            outcome.parse.skippedFiles,
        )
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun undecodableImageKeepsAltTextAndCountsAsSaveFailure() = runBlocking {
        val stack = newStack()
        val svg = File(stagingDir(), "x.svg").apply {
            writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
        }
        val note = ImportedNote(
            title = "SvgNote",
            contentHtml = "<p>%%NOMA_IMPORT_IMAGE_0%%</p>",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            pinned = false,
            archived = false,
            trashedAt = null,
            tagNames = emptyList(),
            folderName = null,
            images = listOf(
                ImportedImage(
                    name = "x.svg",
                    mimeType = "image/svg+xml",
                    stagedFile = svg,
                    sizeBytes = svg.length(),
                    alt = "an svg",
                    token = "%%NOMA_IMPORT_IMAGE_0%%",
                ),
            ),
            sourceName = "svg.md",
        )
        val result = stack.repo.importNotes(listOf(note))
        assertEquals(1, result.report.imported)
        assertEquals(1, result.imageSaveFailures)
        assertEquals("<p>an svg</p>", stack.db.noteDao().getAll().single().contentHtml)
    }
}
