package app.nota.data.importing

import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.rebuildLibraryIndexes
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.repository.refreshFtsEntry
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

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
        // Dateless entries (Codex R26-F2): a ZIP entry written with
        // time 0 reads back with time 0, so the parser's fallback
        // stamp is 0 — the note is dateless. The default keeps the
        // wall clock, as before.
        dateless: Boolean = false,
    ): File {
        val file = File.createTempFile("import-test", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, body) in entries) {
                val entry = ZipEntry(name)
                if (dateless) entry.time = 0
                zip.putNextEntry(entry)
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for ((name, body) in binaries) {
                val entry = ZipEntry(name)
                if (dateless) entry.time = 0
                zip.putNextEntry(entry)
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
    fun overlongImageBasenameStillImports() = runBlocking {
        // (Codex R17-F2): a referenced image whose basename exceeds
        // Android's 255-byte filename limit used to make staging throw
        // (ENAMETOOLONG) and fail the WHOLE import. The staged name
        // is now truncated, so the image embeds under a shortened
        // name and the rest of the run still lands.
        val stack = newStack()
        val longName = "a".repeat(260) + ".png"
        val zip = zipOf(
            linkedMapOf(
                "notes/long.md" to "# Long\n\n![pic](images/$longName)",
                "notes/other.md" to "# Other\n\nplain",
            ),
            mapOf("notes/images/$longName" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownZip(zip)
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.report.failed)
        assertEquals(0, outcome.imageSaveFailures)
        val notes = stack.db.noteDao().getAll()
        assertTrue(
            notes.first { it.title == "Long" }.contentHtml.contains("nota-attachment://"),
        )
        val attachments = stack.db.attachmentDao().getAll()
        assertEquals(1, attachments.size)
        assertTrue(attachments[0].fileName.toByteArray(Charsets.UTF_8).size <= 255)
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
    fun unsaveableImageKeepsAltTextAndCountsAsSaveFailure() = runBlocking {
        val stack = newStack()
        // A staged file that isn't there: AttachmentStore throws on the
        // stream open, deterministically, in any environment (a decode
        // failure would depend on the bitmap shadow under Robolectric).
        // The engine must keep the note, swap the token for the alt
        // text, and count the failure — no silent drop.
        val missing = File(stagingDir(), "gone.png")
        val note = ImportedNote(
            title = "MissingImageNote",
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
                    name = "gone.png",
                    mimeType = "image/png",
                    stagedFile = missing,
                    sizeBytes = 123L,
                    alt = "a missing image",
                    token = "%%NOMA_IMPORT_IMAGE_0%%",
                ),
            ),
            sourceName = "missing.md",
        )
        val result = stack.repo.importNotes(listOf(note))
        assertEquals(1, result.report.imported)
        assertEquals(1, result.imageSaveFailures)
        assertEquals("<p>a missing image</p>", stack.db.noteDao().getAll().single().contentHtml)
    }

    @Test
    fun keepAttachmentsResolveRegardlessOfArchiveOrder() {
        // F1: the parser converts one JSON at a time after a names-only
        // pass, so attachment resolution must not depend on whether an
        // image entry sits before or after its JSON in the archive.
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/late.json" to """{"title":"Late","textContent":"l","attachments":[{"filePath":"Takeout/Keep/late.png","mimetype":"image/png"}],"createdTimestampUsec":1700000000000000}""",
                "Takeout/Keep/early.json" to """{"title":"Early","textContent":"e","attachments":[{"filePath":"Takeout/Keep/early.png","mimetype":"image/png"}],"createdTimestampUsec":1700000100000000}""",
            ),
            mapOf(
                "Takeout/Keep/late.png" to pngBytes,
                "Takeout/Keep/early.png" to pngBytes,
            ),
        )
        val result = KeepTakeoutParser.parse(zip, stagingDir())
        assertEquals(2, result.notes.size)
        assertEquals(0, result.attachmentsSkipped)
        assertEquals(1, result.notes.first { it.title == "Late" }.images.size)
        assertEquals(1, result.notes.first { it.title == "Early" }.images.size)
    }

    @Test
    fun sharedKeepImageEmbedsInBothNotes() = runBlocking {
        // F2: attachments stream to one staged file per entry, shared
        // by every referencing note — a shared image must survive in
        // every note that references it.
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/one.json" to """{"title":"One","textContent":"o","attachments":[{"filePath":"Takeout/Keep/shared.png","mimetype":"image/png"}],"createdTimestampUsec":1700000000000000}""",
                "Takeout/Keep/two.json" to """{"title":"Two","textContent":"t","attachments":[{"filePath":"Takeout/Keep/shared.png","mimetype":"image/png"}],"createdTimestampUsec":1700000100000000}""",
            ),
            mapOf("Takeout/Keep/shared.png" to pngBytes),
        )
        val stack = newStack()
        val outcome = stack.repo.importKeepZip(zip)
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.imageSaveFailures)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "One" }.contentHtml.contains("nota-attachment://"))
        assertTrue(notes.first { it.title == "Two" }.contentHtml.contains("nota-attachment://"))
        assertEquals(2, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun markdownZipMemberOverByteCapIsSkipped() {
        // F3: 34,952,534 CJK characters = 104,857,602 UTF-8 bytes —
        // over the 100 MiB byte cap, but under it as a character
        // count. The old code buffered it and let the char-count check
        // pass; the byte cap at read time must reject it.
        val big = "字".repeat(34_952_534)
        val zip = zipOf(linkedMapOf("big.md" to big))
        val result = MarkdownImporter.parseImportZip(zip, stagingDir())
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(SkippedImportFile("big.md", "File is larger than 100 MB.")),
            result.skippedFiles,
        )
    }

    @Test
    fun importNotesCapsAcrossTheWholeRun() = runBlocking {
        // F4: the engine-level guard — 5,001 identical notes: the
        // first writes, the next 4,999 are duplicates, and the one
        // past the cap is reported as failed, never silently dropped.
        val stack = newStack()
        val notes = (0..5_000).map { i ->
            ImportedNote(
                title = "Cap", contentHtml = "<p>x</p>",
                createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                pinned = false, archived = false, trashedAt = null,
                tagNames = emptyList(), folderName = null, images = emptyList(),
                sourceName = "cap-$i.md",
            )
        }
        val result = stack.repo.importNotes(notes)
        assertEquals(1, result.report.imported)
        assertEquals(4_999, result.report.duplicates)
        assertEquals(1, result.report.failed)
        assertEquals(listOf("cap-5000.md"), result.report.failedNames)
    }

    @Test
    fun markdownZipConvertsEntriesOneAtATime() {
        // R2-F1: a multi-entry ZIP converts incrementally — every
        // entry lands in archive order with its content intact, and
        // the image resolves even though the text is dropped after
        // each conversion. One member carries ~0.9 MiB of text
        // (staying under the ~1 MB per-note bound, R7-F1).
        val zip = zipOf(
            linkedMapOf(
                "notes/one.md" to "# One\n\nfirst",
                "notes/two.md" to "# Two\n\n" + "x".repeat(900_000),
                "notes/three.md" to "# Three\n\n![p](pic.png)",
            ),
            mapOf("notes/pic.png" to pngBytes),
        )
        val result = MarkdownImporter.parseImportZip(zip, stagingDir())
        assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
        assertEquals(listOf("One", "Two", "Three"), result.notes.map { it.title })
        assertEquals("<p>first</p>", result.notes[0].contentHtml)
        assertTrue(result.notes[1].contentHtml.length > 900_000)
        assertTrue(result.notes[1].contentHtml.startsWith("<p>"))
        val three = result.notes[2]
        assertEquals(1, three.images.size)
        assertNotNull(three.images[0].token)
        assertTrue(three.contentHtml.contains(three.images[0].token!!))
    }

    @Test
    fun markdownSelectionCarriesPreselectedSkips() = runBlocking {
        // R2-F2: picks the UI rejected before copying (unsupported
        // type, or over the staging cap) still appear in the report
        // as skipped files — never silently dropped.
        val stack = newStack()
        val dir = stagingDir()
        val md = File(dir, "a.md").apply { writeText("# A\n\nbody") }
        val outcome = stack.repo.importMarkdownSelection(
            listOf(StagedImportFile("a.md", md, 1_700_000_000_000L)),
            listOf(SkippedImportFile("c.pdf", "Not a Markdown, text or ZIP file.")),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(
            listOf(SkippedImportFile("c.pdf", "Not a Markdown, text or ZIP file.")),
            outcome.parse.skippedFiles,
        )
    }

    @Test
    fun unsaveableImageWithNullTokenKeepsAltText() = runBlocking {
        // F5: a Keep-style image (null token, appended at the end)
        // that fails to save must still leave its alt text in the
        // note, as an appended paragraph.
        val stack = newStack()
        val missing = File(stagingDir(), "gone.png")
        val note = ImportedNote(
            title = "MissingKeepImage",
            contentHtml = "<p>Body text</p>",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            pinned = false,
            archived = false,
            trashedAt = null,
            tagNames = emptyList(),
            folderName = null,
            images = listOf(
                ImportedImage(
                    name = "gone.png",
                    mimeType = "image/png",
                    stagedFile = missing,
                    sizeBytes = 123L,
                    alt = "a missing image",
                    token = null,
                ),
            ),
            sourceName = "missing-keep.json",
        )
        val result = stack.repo.importNotes(listOf(note))
        assertEquals(1, result.report.imported)
        assertEquals(1, result.imageSaveFailures)
        assertEquals(
            "<p>Body text</p><p>a missing image</p>",
            stack.db.noteDao().getAll().single().contentHtml,
        )
    }

    @Test
    fun looseFileSelectionCapsAcrossFiles() = runBlocking {
        // R3-F2: a loose-file selection converts each file as it is
        // read — the run cap applies across the whole selection, with
        // the excess reported as skipped, never silently dropped.
        val stack = newStack()
        val dir = stagingDir()
        val staged = (0..5_000).map { i ->
            val file = File(dir, "loose-$i.md").apply { writeText("# Note $i\n\nbody $i") }
            StagedImportFile("loose-$i.md", file, 1_700_000_000_000L + i)
        }
        val outcome = stack.repo.importMarkdownSelection(staged)
        assertEquals(5_000, outcome.report.imported)
        assertEquals(
            listOf(
                SkippedImportFile(
                    "loose-5000.md",
                    "Import limit reached (5,000 notes per run).",
                ),
            ),
            outcome.parse.skippedFiles,
        )
        assertEquals(5_000, stack.db.noteDao().getAll().size)
    }

    @Test
    fun keepTakeoutWithTruncatedNameInventoryReportsIt() {
        // R3-F3 as re-decided by R16-F1: entry names past the 8 MiB
        // retained-name budget used to be dropped from the inventory
        // with a truncation warning and a partial import — but the
        // per-walk name tracking that produced the inventory was
        // itself unbounded, so the walk now trips the budget as a
        // hard quota instead: the whole archive is refused with the
        // quota reason, nothing partial is applied. (ZIP entry names
        // top out at 64 KiB, so ~150 entries at ~60 KiB each clear
        // the budget; the note JSON is written last, past the cut.)
        val entries = LinkedHashMap<String, String>()
        repeat(150) { i ->
            entries["Takeout/Keep/filler-$i-" + "x".repeat(59_900) + ".txt"] = ""
        }
        val noteName = "Takeout/Keep/" + "n".repeat(59_900) + ".json"
        entries[noteName] =
            """{"title":"Hidden","textContent":"never listed","createdTimestampUsec":1700000000000000}"""
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(
                SkippedImportFile(
                    "Takeout ZIP",
                    "That ZIP has too many or too long entry names to import safely.",
                ),
            ),
            result.skippedFiles,
        )
    }

    @Test
    fun truncatedKeepZipIsReportedUnreadable() {
        // R23-F3: a file starting with the local-header signature
        // that yields zero entries is truncated, not an empty
        // archive — it used to report a normal "Imported 0 notes".
        val truncated = File.createTempFile("truncated-keep", ".zip").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04, 0, 0))
        }
        val dir = stagingDir()
        try {
            val result = KeepTakeoutParser.parse(truncated, dir)
            assertEquals(0, result.notes.size)
            assertEquals(
                listOf(
                    SkippedImportFile(
                        "Takeout ZIP",
                        "That file couldn't be read as a ZIP archive.",
                    ),
                ),
                result.skippedFiles,
            )
        } finally {
            truncated.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun validEmptyKeepZipImportsAsEmptyWithoutError() {
        // R23-F3: a valid empty archive (end-of-central-directory
        // record only) still imports as an empty run — no
        // unreadable-file reason.
        val empty = File.createTempFile("empty-keep", ".zip")
        ZipOutputStream(empty.outputStream().buffered()).use { }
        val dir = stagingDir()
        try {
            val result = KeepTakeoutParser.parse(empty, dir)
            assertEquals(0, result.notes.size)
            assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
        } finally {
            empty.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun markdownZipInventoryBudgetTruncatesLongNames() {
        // R4-F1 as re-decided by R16-F1: the walk-1 inventory used to
        // be bounded by the same 8 MiB retained-name budget as the
        // reader, with names past it dropped and the truncation
        // reported — but the per-walk name tracking that produced the
        // inventory was itself unbounded, so the walk now trips the
        // budget as a hard quota instead: the whole archive is
        // refused with the quota reason, nothing partial is applied.
        // (~150 entries at ~60 KiB each clear the budget; the last
        // note sits past the cut.)
        val entries = LinkedHashMap<String, String>()
        repeat(150) { i ->
            entries["notes/filler-$i-" + "x".repeat(59_900) + ".md"] = "# Filler $i\n\nbody"
        }
        entries["notes/" + "z".repeat(59_900) + ".md"] = "# Hidden\n\nnever listed"
        val result = MarkdownImporter.parseImportZip(zipOf(entries), stagingDir())
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(
                SkippedImportFile(
                    "ZIP archive",
                    "That ZIP has too many or too long entry names to import safely.",
                ),
            ),
            result.skippedFiles,
        )
    }

    @Test
    fun duplicatePickerImageNamesStayUnresolved() = runBlocking {
        // R4-F2: two picked images sharing a display name are
        // ambiguous — the reference stays unresolved (alt kept,
        // counted) instead of silently embedding the last one. A
        // uniquely named image in the same selection still embeds.
        val stack = newStack()
        val dir = stagingDir()
        val first = File(dir, "img-a.png").apply { writeBytes(pngBytes) }
        val second = File(dir, "img-b.png").apply { writeBytes(pngBytes) }
        val unique = File(dir, "only.png").apply { writeBytes(pngBytes) }
        val amb = File(dir, "a.md").apply { writeText("# A\n\n![alt](image.png)") }
        val ok = File(dir, "b.md").apply { writeText("# B\n\n![ok](only.png)") }
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.md", amb, 1_700_000_000_000L),
                StagedImportFile("b.md", ok, 1_700_000_000_000L),
                StagedImportFile("image.png", first, 1_700_000_000_000L),
                StagedImportFile("image.png", second, 1_700_000_000_000L),
                StagedImportFile("only.png", unique, 1_700_000_000_000L),
            ),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(1, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertEquals("<p>alt</p>", notes.first { it.title == "A" }.contentHtml)
        assertTrue(notes.first { it.title == "B" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun sharedKeepImageStagesOnce() {
        // R4-F3: one staged file per attachment entry, shared by
        // every note that references it — no per-note copies on disk,
        // and the stored name stays the image's own name.
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/one.json" to """{"title":"One","textContent":"o","attachments":[{"filePath":"Takeout/Keep/shared.png","mimetype":"image/png"}],"createdTimestampUsec":1700000000000000}""",
                "Takeout/Keep/two.json" to """{"title":"Two","textContent":"t","attachments":[{"filePath":"Takeout/Keep/shared.png","mimetype":"image/png"}],"createdTimestampUsec":1700000100000000}""",
            ),
            mapOf("Takeout/Keep/shared.png" to pngBytes),
        )
        val dir = stagingDir()
        val result = KeepTakeoutParser.parse(zip, dir)
        assertEquals(2, result.notes.size)
        assertEquals(0, result.attachmentsSkipped)
        val staged = dir.listFiles()?.toList().orEmpty()
        assertEquals(1, staged.size)
        assertEquals("shared.png", staged[0].name)
        val first = result.notes.first { it.title == "One" }.images.single()
        val second = result.notes.first { it.title == "Two" }.images.single()
        assertEquals(first.stagedFile, second.stagedFile)
        assertFalse(first.deleteAfterUse)
        assertFalse(second.deleteAfterUse)
    }

    @Test
    fun importSurvivesAccountSwitchMidRun() = runBlocking {
        // R5-F1: an account switch mid-import must not split the run
        // across accounts or write into the closed instance — the run
        // stays pinned to the account it started in. The third note
        // carries an image, so its attachment row is pinned too. The
        // production constructor is used deliberately: the forDatabase
        // seam hands over a database built for a mismatched account
        // id, so openAdditional would target the wrong file.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-switch-a-$seq"
        val otherId = "import-switch-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        try {
            fun plain(title: String, at: Long) = ImportedNote(
                title, "<p>x</p>", at, at, false, false, null,
                emptyList(), null, emptyList(), "$title.md",
            )
            val imageFile = File.createTempFile("switch-pic", ".png")
                .apply { writeBytes(pngBytes) }
            val notes = listOf(
                plain("SwitchA", 1_700_000_000_000L),
                plain("SwitchB", 1_700_000_010_000L),
                ImportedNote(
                    "SwitchPic", "<p>%%NOMA_IMPORT_IMAGE_0%%</p>",
                    1_700_000_020_000L, 1_700_000_020_000L,
                    false, false, null, emptyList(), null,
                    listOf(
                        ImportedImage(
                            "pic.png", "image/png", imageFile,
                            pngBytes.size.toLong(), "pic",
                            "%%NOMA_IMPORT_IMAGE_0%%",
                        ),
                    ),
                    "SwitchPic.md",
                ),
                plain("SwitchC", 1_700_000_030_000L),
                plain("SwitchD", 1_700_000_040_000L),
            )
            var switched = false
            val result = repo.importNotes(notes) { done, _ ->
                if (done == 2 && !switched) {
                    switched = true
                    assertTrue(provider.switchTo(otherId))
                }
            }
            assertTrue(switched)
            assertEquals(5, result.report.imported)
            assertEquals(0, result.report.failed)
            assertEquals(otherId, provider.accountId.value)
            // Nothing leaked into the account switched to mid-run.
            assertEquals(0, provider.current.noteDao().getAll().size)
            assertEquals(0, provider.current.attachmentDao().getAll().size)
            // Everything landed in the account the import started in.
            val startDb = provider.openAdditional(startId)
            try {
                assertEquals(5, startDb.noteDao().getAll().size)
                assertEquals(1, startDb.attachmentDao().getAll().size)
            } finally {
                provider.closeAdditional(startDb)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun failedStagingDeletesItsTempDir() = runBlocking {
        // R5-F2: a stage that throws after creating its directory
        // must not leak the directory — the store owns it, not the
        // stage.
        val store = PendingImportStore()
        val dir = File.createTempFile("import-stage", ".dir")
            .apply { delete(); mkdir() }
        store.stageAndRun(
            newTempDir = { dir },
            stage = { staged ->
                File(staged, "partial.bin").writeBytes(byteArrayOf(1, 2, 3))
                error("boom")
            },
            run = { _, _ -> error("must not run") },
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (store.state.value !is PendingImportState.Failed &&
            System.currentTimeMillis() < deadline
        ) {
            delay(50)
        }
        assertTrue(store.state.value is PendingImportState.Failed)
        assertFalse(dir.exists())
    }

    @Test
    fun keepAggregateTextBudgetSkipsOverBudgetNotes() {
        // R6-F1: nineteen 900K-char Keep notes total ~17.1M chars —
        // past the 16M aggregate budget. Eighteen parse; the last is
        // reported as skipped, never silently dropped. Notes stay at
        // 900K chars on purpose: a single note past the ~1 MB
        // per-note bound is refused for its own size (R7-F1), so the
        // aggregate test must use individually legal notes.
        val body = "y".repeat(900_000)
        val entries = linkedMapOf<String, String>()
        repeat(19) { i ->
            entries["Takeout/Keep/big-$i.json"] =
                """{"title":"Big$i","textContent":"$body","createdTimestampUsec":${1_700_000_000_000L + i}}"""
        }
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(18, result.notes.size)
        assertEquals((0 until 18).map { "Big$it" }, result.notes.map { it.title })
        assertTrue(result.notes.all { it.contentHtml.length > 900_000 })
        assertEquals(
            listOf(SkippedImportFile("big-18.json", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun keepAggregateTextBudgetCountsTitles() {
        // R19-F1: the Keep pass-2 draft check used to count only the
        // converted body toward the 16M-char aggregate budget, so
        // nineteen notes with ~1 MB titles and one-character bodies
        // all fit while retaining ~17M chars of titles. Titles count
        // now: eighteen parse (18 × 900,007 ≈ 16.2M chars) and the
        // last is reported as skipped, never silently dropped. The
        // raw JSON stays under its own 16M-char raw bound, so the
        // skip comes from the aggregate check, not the raw read.
        val title = "T".repeat(900_000)
        val entries = linkedMapOf<String, String>()
        repeat(19) { i ->
            entries["Takeout/Keep/big-$i.json"] =
                """{"title":"$title","textContent":"x","createdTimestampUsec":${1_700_000_000_000L + i}}"""
        }
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(18, result.notes.size)
        assertTrue(result.notes.all { it.title == title })
        assertEquals(
            listOf(SkippedImportFile("big-18.json", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun keepHtmlExpansionCountsAgainstBudget() {
        // R7-F3: HTML expansion (& → &amp;) grows the converted body
        // past the raw JSON length — a note whose raw JSON fits the
        // remaining budget but whose converted HTML exceeds it is
        // dropped at conversion time, not retained until the final
        // addNote loop. Eighteen 900K-char fillers leave ~577K chars
        // of budget; a 190K-amp note reads within it (~190K raw) but
        // converts to ~950K chars of HTML, past what remains.
        val filler = "y".repeat(900_000)
        val amps = "&".repeat(190_000)
        val entries = linkedMapOf<String, String>()
        repeat(18) { i ->
            entries["Takeout/Keep/big-$i.json"] =
                """{"title":"Big$i","textContent":"$filler","createdTimestampUsec":${1_700_000_000_000L + i}}"""
        }
        entries["Takeout/Keep/amps.json"] =
            """{"title":"Amps","textContent":"$amps","createdTimestampUsec":1700000200000000}"""
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(18, result.notes.size)
        assertEquals((0 until 18).map { "Big$it" }, result.notes.map { it.title })
        assertEquals(
            listOf(SkippedImportFile("amps.json", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun hugeKeepTaskListIsReportedTooLarge() {
        // R25-F1: 20,000 tiny task items — ~640K raw JSON chars, well
        // under the 16M raw bound — expand into ~1.4M bytes of HTML.
        // The note is refused with the per-note reason BEFORE the
        // whole list is built, and imports nothing from that entry;
        // the small note proves the run is intact.
        val items = buildString {
            append('[')
            repeat(20_000) { i ->
                if (i > 0) append(',')
                append("""{"text":"x","isChecked":false}""")
            }
            append(']')
        }
        val entries = linkedMapOf(
            "Takeout/Keep/huge.json" to
                """{"title":"Huge","listContent":$items,"createdTimestampUsec":1700000000000000}""",
            "Takeout/Keep/small.json" to
                """{"title":"Small","textContent":"fine","createdTimestampUsec":1700000100000000}""",
        )
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(listOf("Small"), result.notes.map { it.title })
        assertEquals(
            listOf(SkippedImportFile("huge.json", ImportCaps.NOTE_TOO_LARGE_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun largeKeepTaskListStillImportsWhole() {
        // R25-F1, no-false-refusal pin: 10,000 items expand to ~700K
        // bytes of HTML — under the 1 MB per-note bound — so the whole
        // list imports, last item included.
        val items = buildString {
            append('[')
            repeat(10_000) { i ->
                if (i > 0) append(',')
                append("""{"text":"item-$i","isChecked":${i % 2 == 0}}""")
            }
            append(']')
        }
        val entries = linkedMapOf(
            "Takeout/Keep/big-list.json" to
                """{"title":"BigList","listContent":$items,"createdTimestampUsec":1700000000000000}""",
        )
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(listOf("BigList"), result.notes.map { it.title })
        assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
        assertTrue(result.notes[0].contentHtml.contains("item-9999"))
    }

    @Test
    fun ampHeavyKeepTextIsReportedTooLarge() {
        // R25-F1 sibling: a textContent of '&' expands 5x through
        // PlainTextCodec before any size check runs. 300K amps read as
        // ~300K raw chars but convert to ~1.5M bytes of HTML — refused
        // with the per-note reason before the expansion is built.
        val amps = "&".repeat(300_000)
        val entries = linkedMapOf(
            "Takeout/Keep/amps.json" to
                """{"title":"Amps","textContent":"$amps","createdTimestampUsec":1700000000000000}""",
        )
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(SkippedImportFile("amps.json", ImportCaps.NOTE_TOO_LARGE_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun whitespaceHeavyKeepTextStillImports() {
        // The textContent guard counts only non-whitespace bytes (a
        // lower bound on the output): 1.5M spaces around one word
        // expand to almost nothing, so the note still imports.
        val text = "hello" + " ".repeat(1_500_000)
        val entries = linkedMapOf(
            "Takeout/Keep/spaces.json" to
                """{"title":"Spaces","textContent":"$text","createdTimestampUsec":1700000000000000}""",
        )
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(listOf("Spaces"), result.notes.map { it.title })
        assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
    }

    @Test
    fun overLimitNoteIsRefusedByEngine() = runBlocking {
        // R7-F1 (defence in depth): a note handed to the engine past
        // the per-note bound is refused, never written — so the
        // run-end rebuild cannot fail on its row. The parsers refuse
        // it earlier with the specific reason (addNote); the engine
        // reports it as failed, and the small note still imports.
        val stack = newStack()
        val big = ImportedNote(
            title = "Big", contentHtml = "<p>" + "x".repeat(1_100_000) + "</p>",
            createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = null, images = emptyList(),
            sourceName = "big.md",
        )
        val small = ImportedNote(
            title = "Small", contentHtml = "<p>small</p>",
            createdAt = 1_700_000_010_000L, updatedAt = 1_700_000_010_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = null, images = emptyList(),
            sourceName = "small.md",
        )
        val result = stack.repo.importNotes(listOf(big, small))
        assertEquals(1, result.report.imported)
        assertEquals(0, result.report.duplicates)
        assertEquals(1, result.report.failed)
        assertEquals(listOf("big.md"), result.report.failedNames)
        assertEquals(listOf("Small"), stack.db.noteDao().getAll().map { it.title })
    }

    @Test
    fun dedicatedHandleSurvivesAccountSwitch() = runBlocking {
        // R7-F2: the import finish runs on a dedicated handle so a
        // switch can never close it mid-rebuild — the handle must
        // stay usable after the account switches away (switchTo only
        // ever closes the live instance).
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-dedicated-a-$seq"
        val otherId = "import-dedicated-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        try {
            val dedicated = provider.openDedicated(startId)
            try {
                dedicated.noteDao().upsert(
                    NoteEntity(
                        id = "d1", title = "Dedicated",
                        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                    ),
                )
                assertTrue(provider.switchTo(otherId))
                assertEquals(otherId, provider.accountId.value)
                assertEquals(1, dedicated.noteDao().getAll().size)
                assertEquals("Dedicated", dedicated.noteDao().getAll().single().title)
                assertEquals(0, provider.current.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(dedicated)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun importSurvivesSwitchDuringFinish() = runBlocking {
        // R7-F2: the finish (rebuild + reconcile) runs on a dedicated
        // handle, not under the switch lock — a switch overlapping
        // the finish must neither break the run nor split it: every
        // note lands whole in the account the import started in. The
        // switch fires from the last progress callback, so it races
        // the finish itself.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-finish-a-$seq"
        val otherId = "import-finish-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        try {
            val notes = (0 until 300).map { i ->
                ImportedNote(
                    "Finish$i", "<p>x</p>",
                    1_700_000_000_000L + i, 1_700_000_000_000L + i,
                    false, false, null, emptyList(), null, emptyList(), "finish-$i.md",
                )
            }
            var switched = false
            var switchThread: Thread? = null
            val result = repo.importNotes(notes) { done, total ->
                if (done == total && !switched) {
                    switched = true
                    switchThread = thread { provider.switchTo(otherId) }
                }
            }
            switchThread?.join(5_000)
            assertTrue(switched)
            assertEquals(otherId, provider.accountId.value)
            assertEquals(300, result.report.imported)
            assertEquals(0, result.report.failed)
            val startDb = provider.openAdditional(startId)
            try {
                assertEquals(300, startDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(startDb)
            }
            val otherDb = provider.openAdditional(otherId)
            try {
                assertEquals(0, otherDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(otherDb)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun markdownSelectionEnforcesAggregateTextBudget() = runBlocking {
        // R6-F1 (merge path): ninety 200K-char loose files total
        // ~18M chars — past the 16M budget. 83 import (83 × ~200K
        // ≈ 16.6M chars); the rest are reported as skipped.
        // Files stay at 200K chars on purpose: a single ~2M-char
        // note cannot be read back — one row exceeds Android's 2 MB
        // CursorWindow, so getAll() throws (a pre-existing product
        // limit, not this round's scope).
        val stack = newStack()
        val dir = stagingDir()
        val staged = (0 until 90).map { i ->
            val file = File(dir, "big-$i.md").apply { writeText("# Big$i\n\n" + "x".repeat(200_000)) }
            StagedImportFile("big-$i.md", file, 1_700_000_000_000L + i)
        }
        val outcome = stack.repo.importMarkdownSelection(staged)
        assertEquals(83, outcome.report.imported)
        assertEquals(0, outcome.report.failed)
        assertEquals(
            (83 until 90).map {
                SkippedImportFile("big-$it.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)
            },
            outcome.parse.skippedFiles,
        )
        assertEquals(83, stack.db.noteDao().getAll().size)
    }

    @Test
    fun importSurvivesAwayAndBackSwitchMidRun() = runBlocking {
        // R8-F1: an away-and-back switch (A→B→A) inside ONE
        // inter-note gap defeated the old pinning: dbForWrite()
        // checked only the account id, saw A again, and returned
        // the handle captured at run start — which the A→B switch
        // had closed (the B→A switch published a NEW instance).
        // Every later note then failed on the closed handle. The
        // run now writes on a dedicated sibling handle, so the
        // double switch is a non-event: all five notes — the image
        // note included — land in A, none in B, none failed. The
        // production constructor is used for the same reason as
        // R5-F1's test: the forDatabase seam's account id would not
        // match the files switchTo builds.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-aba-a-$seq"
        val otherId = "import-aba-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        try {
            fun plain(title: String, at: Long) = ImportedNote(
                title, "<p>x</p>", at, at, false, false, null,
                emptyList(), null, emptyList(), "$title.md",
            )
            val imageFile = File.createTempFile("aba-pic", ".png")
                .apply { writeBytes(pngBytes) }
            val notes = listOf(
                plain("AbaA", 1_700_000_000_000L),
                plain("AbaB", 1_700_000_010_000L),
                plain("AbaC", 1_700_000_020_000L),
                ImportedNote(
                    "AbaPic", "<p>%%NOMA_IMPORT_IMAGE_0%%</p>",
                    1_700_000_030_000L, 1_700_000_030_000L,
                    false, false, null, emptyList(), null,
                    listOf(
                        ImportedImage(
                            "pic.png", "image/png", imageFile,
                            pngBytes.size.toLong(), "pic",
                            "%%NOMA_IMPORT_IMAGE_0%%",
                        ),
                    ),
                    "AbaPic.md",
                ),
                plain("AbaD", 1_700_000_040_000L),
            )
            var switched = false
            val result = repo.importNotes(notes) { done, _ ->
                if (done == 2 && !switched) {
                    switched = true
                    // BOTH switches inside the same gap — no note
                    // write runs while the account is B.
                    assertTrue(provider.switchTo(otherId))
                    assertTrue(provider.switchTo(startId))
                }
            }
            assertTrue(switched)
            assertEquals(startId, provider.accountId.value)
            assertEquals(5, result.report.imported)
            assertEquals(0, result.report.failed)
            assertEquals(5, provider.current.noteDao().getAll().size)
            assertEquals(1, provider.current.attachmentDao().getAll().size)
            val otherDb = provider.openAdditional(otherId)
            try {
                assertEquals(0, otherDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(otherDb)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun importRunNoteVisibleOnFreshLibraryCollection() = runBlocking {
        // (Codex R17-F1, fix-20 decision — ACCEPT + DOCUMENT):
        // the engine writes through a dedicated sibling handle,
        // and Room 2.8.5's invalidation log is a per-connection
        // TEMP table, so a sibling instance's writes can never be
        // seen by the live instance's tracker — no refresh call
        // can make a live library Flow re-emit (the fix-19
        // diagnostic proved it from Room's own source). This test
        // asserts the DOCUMENTED behaviour instead: the import
        // lands, and a FRESH collection of the library Flow (a
        // collector restart, e.g. navigating back from Settings)
        // shows the imported note. It does NOT assert live
        // re-emission — that expectation was refuted by the
        // diagnostic.
        val stack = newStack()
        val notes = listOf(
            ImportedNote(
                "FlowNote", "<p>hello</p>",
                1_700_000_000_000L, 1_700_000_000_000L,
                false, false, null, emptyList(), null, emptyList(),
                "FlowNote.md",
            ),
        )
        // Subscribe FIRST and await the pre-import (empty) emission
        // before launching the import — the same race guard as
        // before: a one-note import landing before the collector's
        // first emission would make take(1) return the note and
        // hide whether the import itself ran.
        val emissions = CopyOnWriteArrayList<List<NoteEntity>>()
        val collector = async(Dispatchers.Default) {
            stack.db.noteDao().observeAll().take(1).collect { batch ->
                emissions += batch
            }
        }
        try {
            settleUntil { emissions.size >= 1 }
            assertEquals(0, emissions[0].size)
            val importJob = async(Dispatchers.IO) { stack.repo.importNotes(notes) }
            val result = withTimeout(15_000) { importJob.await() }
            assertEquals(1, result.report.imported)
        } finally {
            collector.cancel()
        }
        // A FRESH collection — the collector-restart shape — must
        // see the imported note. The collector runs on
        // Dispatchers.Default and THIS thread pumps Robolectric's
        // paused main looper in the settle loop while waiting
        // (Room dispatches on the main thread; the pump MUST run
        // on this thread — ShadowPausedLooper.java:761). Every
        // wait is bounded at 15 s, so a missing note fails this
        // test in seconds instead of hanging the shard.
        val fresh = CopyOnWriteArrayList<List<NoteEntity>>()
        val freshCollector = async(Dispatchers.Default) {
            stack.db.noteDao().observeAll().take(1).collect { batch ->
                fresh += batch
            }
        }
        try {
            settleUntil { fresh.size >= 1 }
            assertEquals(1, fresh.size)
            assertEquals(listOf("FlowNote"), fresh[0].map { it.title })
        } finally {
            freshCollector.cancel()
        }
    }

    // Fix-19 diagnostic (Codex R17-F1), kept in fix 20 as the
    // control: a write through the LIVE instance itself re-emits,
    // proving the observer/notification path works under this
    // harness — the sibling-handle gap is real, not a harness
    // artifact. The other two diagnostic tests (shared-log read,
    // sibling-write refresh) were removed in fix 20: their
    // shared-log premise is refuted by Room 2.8.5's source (the
    // invalidation log is a per-connection TEMP table), so they
    // are not regressions to keep.
    @Test
    fun liveWriteReemitsLibraryFlow() = runBlocking {
        // CONTROL: a write through the LIVE instance itself. If this
        // does not re-emit either, the observer/notification path is
        // broken under this harness and the sibling-handle gap is a
        // harness artifact, not a real gap.
        val stack = newStack()
        val emissions = CopyOnWriteArrayList<List<NoteEntity>>()
        val collector = async(Dispatchers.Default) {
            stack.db.noteDao().observeAll().take(2).collect { batch ->
                emissions += batch
            }
        }
        try {
            settleUntil { emissions.size >= 1 }
            assertTrue(emissions.isNotEmpty())
            assertEquals(0, emissions[0].size)
            val writeJob = async(Dispatchers.IO) {
                stack.db.noteDao().upsert(
                    NoteEntity(
                        id = "diag-live-1",
                        title = "DiagLive",
                        contentHtml = "<p>live</p>",
                        plainText = "live",
                        createdAt = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L,
                    ),
                )
            }
            withTimeout(15_000) { writeJob.await() }
            settleUntil { emissions.size >= 2 }
            println("control: emissions=${emissions.size} titles=${emissions.flatten().map { it.title }}")
            assertEquals(2, emissions.size)
            assertEquals(listOf("DiagLive"), emissions[1].map { it.title })
        } finally {
            collector.cancel()
        }
    }

    /** Poll [condition] while idling Robolectric's paused main looper,
     *  so main-thread-dispatched work (e.g. Room's invalidation
     *  notification) can run. Bounded: gives up after [deadlineMs]
     *  and lets the caller fail on the unmet condition instead of
     *  hanging the shard. */
    private fun settleUntil(deadlineMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            if (System.currentTimeMillis() > end) break
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun accountSwitchDoesNotWaitForImageConversion() = runBlocking {
        // R8-F2: image conversion (decode/scale/JPEG-encode) inside
        // a note write must not stall an account switch — the old
        // shape held the switch mutex across the whole note write,
        // so a switch arriving mid-conversion busy-spun on its
        // caller (the main thread for an auth change) until the
        // image finished. The staged image here is a FIFO whose
        // writer parks INSIDE the first stream open — bytes
        // written, stream held open — so the import is provably
        // stalled in the image phase (waiting for EOF on the first
        // read, or for a writer on the next open) until the test
        // releases it, while the switch is attempted. Every wait
        // is bounded, so a broken rendezvous fails this test in
        // seconds rather than hanging.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-fifo-a-$seq"
        val otherId = "import-fifo-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        val fifo = File(stagingDir(), "pic.png")
        val mkfifo = ProcessBuilder("mkfifo", fifo.absolutePath)
            .redirectErrorStream(true).start()
        assertEquals(0, mkfifo.waitFor())
        val firstOpenParked = CountDownLatch(1)
        val releaseFirstOpen = CountDownLatch(1)
        val stopWriter = AtomicBoolean(false)
        val writer = thread(isDaemon = true) {
            var first = true
            while (!stopWriter.get()) {
                try {
                    FileOutputStream(fifo).use { out ->
                        out.write(pngBytes)
                        out.flush()
                        if (first) {
                            first = false
                            firstOpenParked.countDown()
                            // Park with the stream OPEN: the
                            // pipeline cannot finish the image
                            // until this is released.
                            releaseFirstOpen.await(
                                15,
                                java.util.concurrent.TimeUnit.SECONDS,
                            )
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
        try {
            val note = ImportedNote(
                "FifoPic", "<p>%%NOMA_IMPORT_IMAGE_0%%</p>",
                1_700_000_000_000L, 1_700_000_000_000L,
                false, false, null, emptyList(), null,
                listOf(
                    ImportedImage(
                        "pic.png", "image/png", fifo,
                        pngBytes.size.toLong(), "pic",
                        "%%NOMA_IMPORT_IMAGE_0%%",
                    ),
                ),
                "FifoPic.md",
            )
            val importJob = async(Dispatchers.IO) { repo.importNotes(listOf(note)) }
            // The pipeline has opened the staged file and the
            // writer is parked holding that stream open —
            // conversion is under way and cannot complete until
            // the release below.
            assertTrue(
                firstOpenParked.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            var switchReturned = false
            val switchThread = thread {
                provider.switchTo(otherId)
                switchReturned = true
            }
            switchThread.join(1_200)
            assertFalse(
                "switch waited on image conversion",
                switchThread.isAlive,
            )
            assertTrue(switchReturned)
            assertEquals(otherId, provider.accountId.value)
            releaseFirstOpen.countDown()
            val result = withTimeoutOrNull(30_000) { importJob.await() }
            assertNotNull(
                "import did not finish after the image was released",
                result,
            )
            result!!
            assertEquals(1, result.report.imported)
            assertEquals(0, result.report.failed)
            assertEquals(0, result.imageSaveFailures)
            // The note and its image landed in the account the
            // import started in, not the one switched to mid-image.
            val startDb = provider.openAdditional(startId)
            try {
                val notes = startDb.noteDao().getAll()
                assertEquals(1, notes.size)
                assertTrue(notes.single().contentHtml.contains("nota-attachment://"))
                assertEquals(1, startDb.attachmentDao().getAll().size)
            } finally {
                provider.closeAdditional(startDb)
            }
        } finally {
            stopWriter.set(true)
            releaseFirstOpen.countDown()
            runCatching { fifo.delete() }
            writer.join(2_000)
            provider.current.close()
        }
    }

    @Test
    fun importSurvivesRapidAccountSwitchesMidRun() = runBlocking {
        // R6-F3 (stress): switches hammering the run from another
        // thread must not fail or split a single note — every write
        // lands whole in the account the import started in.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-storm-a-$seq"
        val otherId = "import-storm-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        try {
            val notes = (0 until 30).map { i ->
                ImportedNote(
                    "Storm$i", "<p>x</p>",
                    1_700_000_000_000L + i, 1_700_000_000_000L + i,
                    false, false, null, emptyList(), null, emptyList(), "storm-$i.md",
                )
            }
            val stop = AtomicBoolean(false)
            val storm = thread {
                var toOther = true
                while (!stop.get()) {
                    provider.switchTo(if (toOther) otherId else startId)
                    toOther = !toOther
                }
            }
            val result = try {
                repo.importNotes(notes)
            } finally {
                stop.set(true)
                storm.join(10_000)
            }
            assertEquals(30, result.report.imported)
            assertEquals(0, result.report.failed)
            // Nothing leaked into the account switched to mid-run.
            val otherDb = provider.openAdditional(otherId)
            try {
                assertEquals(0, otherDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(otherDb)
            }
            // Everything landed in the account the import started in.
            val startDb = provider.openAdditional(startId)
            try {
                assertEquals(30, startDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(startDb)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun importKeepZipCapturesAccountBeforeParsing() = runBlocking {
        // R18-F1: the target account is captured BEFORE parsing, not
        // when importNotes opens its handle after parsing — a switch
        // during parsing must not move the run to the other account.
        // This is the fix-13 storm-test capture race, now raised by
        // Codex: the old shape captured provider.current after the
        // parse, so with the storm hammering switches the import
        // landed in whichever account was current then. Ten 900K-
        // char notes make the parse window long enough that the
        // storm is guaranteed to switch inside it; the run must
        // still land whole in the account it started in.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-parse-a-$seq"
        val otherId = "import-parse-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        val store = AttachmentStore(context, provider)
        val repo = NoteImportRepository(context, provider, store)
        try {
            val body = "y".repeat(900_000)
            val entries = linkedMapOf<String, String>()
            repeat(10) { i ->
                entries["Takeout/Keep/parse-$i.json"] =
                    """{"title":"Parse$i","textContent":"$body","createdTimestampUsec":${1_700_000_000_000L + i}}"""
            }
            val zip = zipOf(entries)
            val stop = AtomicBoolean(false)
            val storm = thread {
                var toOther = true
                while (!stop.get()) {
                    provider.switchTo(if (toOther) otherId else startId)
                    toOther = !toOther
                }
            }
            val outcome = try {
                repo.importKeepZip(zip)
            } finally {
                stop.set(true)
                storm.join(10_000)
            }
            assertEquals(10, outcome.report.imported)
            assertEquals(0, outcome.report.failed)
            // Nothing leaked into the account switched to mid-parse.
            val otherDb = provider.openAdditional(otherId)
            try {
                assertEquals(0, otherDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(otherDb)
            }
            // Everything landed in the account the import started in.
            val startDb = provider.openAdditional(startId)
            try {
                assertEquals(10, startDb.noteDao().getAll().size)
            } finally {
                provider.closeAdditional(startDb)
            }
        } finally {
            provider.current.close()
        }
    }

    @Test
    fun looseFileOverRemainingBudgetIsSkippedBeforeParsing() = runBlocking {
        // R9-F1: a loose file past the REMAINING run budget is
        // skipped before it is materialized or parsed. Eighty-three
        // 200K-char fillers leave ~176K chars of budget; a 500K-char
        // file with an unresolvable image reference is over what
        // remains. Before the fix it was read in full and parsed —
        // the image count leaked into the report even though the
        // note was then dropped by the merge-time budget check.
        val stack = newStack()
        val dir = stagingDir()
        val staged = (0 until 83).map { i ->
            val file = File(dir, "big-$i.md").apply { writeText("# Big$i\n\n" + "x".repeat(200_000)) }
            StagedImportFile("big-$i.md", file, 1_700_000_000_000L + i)
        } + listOf(
            StagedImportFile(
                "over.md",
                File(dir, "over.md").apply {
                    writeText("# Over\n\n" + "x".repeat(500_000) + "\n\n![ghost](missing.png)")
                },
                1_700_000_100_000L,
            ),
        )
        val outcome = stack.repo.importMarkdownSelection(staged)
        assertEquals(83, outcome.report.imported)
        assertEquals(0, outcome.report.failed)
        assertEquals(
            listOf(SkippedImportFile("over.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            outcome.parse.skippedFiles,
        )
        assertEquals(0, outcome.parse.imagesUnresolved)
        assertEquals(83, stack.db.noteDao().getAll().size)
    }

    @Test
    fun ambiguousKeepAttachmentMatchesStayUnresolved() {
        // R9-F2: two entries sharing a suffix or basename are
        // ambiguous — the note embeds neither, the attachment is
        // counted, and the file name stays in the note (alt kept).
        // A uniquely matched fallback still resolves.
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/dup-suffix.json" to """{"title":"DupSuffix","textContent":"see","attachments":[{"filePath":"photo.jpg","mimetype":"image/png"}],"createdTimestampUsec":1700000000000000}""",
                "Takeout/Keep/dup-base.json" to """{"title":"DupBase","textContent":"see2","attachments":[{"filePath":"sub/photo.jpg","mimetype":"image/png"}],"createdTimestampUsec":1700000100000000}""",
                "Takeout/Keep/unique.json" to """{"title":"Unique","textContent":"see3","attachments":[{"filePath":"unique.jpg","mimetype":"image/png"}],"createdTimestampUsec":1700000200000000}""",
            ),
            mapOf(
                "Takeout/Keep/photo.jpg" to pngBytes,
                "Takeout/Other/photo.jpg" to pngBytes,
                "Takeout/Other/unique.jpg" to pngBytes,
            ),
        )
        val dir = stagingDir()
        val result = KeepTakeoutParser.parse(zip, dir)
        assertEquals(3, result.notes.size)
        assertEquals(2, result.attachmentsSkipped)

        val dupSuffix = result.notes.first { it.title == "DupSuffix" }
        assertEquals(0, dupSuffix.images.size)
        assertEquals("<p>see</p><p>photo.jpg</p>", dupSuffix.contentHtml)

        val dupBase = result.notes.first { it.title == "DupBase" }
        assertEquals(0, dupBase.images.size)
        assertEquals("<p>see2</p><p>photo.jpg</p>", dupBase.contentHtml)

        val unique = result.notes.first { it.title == "Unique" }
        assertEquals(1, unique.images.size)
        assertEquals("unique.jpg", unique.images[0].name)

        // Only the uniquely matched attachment was staged.
        assertEquals(listOf("unique.jpg"), dir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun keepJsonLargerThanRemainingBudgetStillImportsWhenBodyFits() {
        // R10-F2: the raw JSON is read under its own 16M-char bound,
        // not the remaining aggregate budget. Eighteen 900K-char
        // fillers leave ~577K chars of budget; a note whose body is
        // four characters but whose JSON carries 700K chars of
        // ignored metadata reads within the raw bound and imports,
        // while a note whose BODY genuinely exceeds what remains is
        // still skipped with the aggregate reason. Before the fix the
        // short note was skipped at the read, with the aggregate
        // reason, even though its body fit.
        val filler = "y".repeat(900_000)
        val entries = linkedMapOf<String, String>()
        repeat(18) { i ->
            entries["Takeout/Keep/big-$i.json"] =
                """{"title":"Big$i","textContent":"$filler","createdTimestampUsec":${1_700_000_000_000L + i}}"""
        }
        entries["Takeout/Keep/small.json"] =
            """{"title":"Small","textContent":"tiny","padding":"${"z".repeat(700_000)}","createdTimestampUsec":1700000000000000}"""
        entries["Takeout/Keep/bigbody.json"] =
            """{"title":"BigBody","textContent":"${"w".repeat(600_000)}","createdTimestampUsec":1700000100000000}"""
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(19, result.notes.size)
        assertEquals((0 until 18).map { "Big$it" } + "Small", result.notes.map { it.title })
        assertEquals(
            listOf(SkippedImportFile("bigbody.json", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun keepEntryPastRawBoundIsSkippedWithItsOwnReason() {
        // R10-F2: the raw-entry bound itself — a single JSON past
        // 16M decoded characters is skipped with the raw-entry
        // reason, not the aggregate reason (the aggregate budget was
        // untouched: nothing else was imported).
        val entries = linkedMapOf(
            "Takeout/Keep/huge.json" to
                """{"title":"Huge","textContent":"tiny","padding":"${"z".repeat(17_000_000)}","createdTimestampUsec":1700000000000000}""",
        )
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(SkippedImportFile("huge.json", ImportCaps.RAW_ENTRY_TOO_LARGE_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun extractedImageBudgetCapsAcrossZipsInOneSelection() = runBlocking {
        // R11-F1: the extracted-image budget is shared across the
        // ZIPs of one selection — the second ZIP's image past the
        // remaining budget is not staged, so its reference stays
        // unresolved (counted) instead of expanding the shared
        // staging directory. Each 1x1 PNG is 70 bytes; a 100-byte
        // budget fits the first and leaves 30 for the second.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf("a.md" to "# A\n\n![a](a.png)"),
            mapOf("a.png" to pngBytes),
        )
        val zipB = zipOf(
            linkedMapOf("b.md" to "# B\n\n![b](b.png)"),
            mapOf("b.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(1, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "A" }.contentHtml.contains("nota-attachment://"))
        assertEquals("<p>b</p>", notes.first { it.title == "B" }.contentHtml)
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun unreferencedZipImagesDoNotConsumeSharedBudget() = runBlocking {
        // R14-F1: walk 2 used to stage EVERY image entry and charge
        // it against the shared budget before any note was parsed —
        // an earlier ZIP full of unreferenced images could exhaust
        // the budget and leave a later ZIP's referenced images
        // unstaged. Each 1x1 PNG is 70 bytes; a 100-byte budget fits
        // one image. ZIP A holds two images no note references; ZIP
        // B references its image. Only B's image may consume the
        // budget — under the old shape A's first image took 70 of
        // the 100 bytes and B's reference stayed unresolved.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf("a.md" to "# A\n\nno images here"),
            mapOf("a1.png" to pngBytes, "a2.png" to pngBytes),
        )
        val zipB = zipOf(
            linkedMapOf("b.md" to "# B\n\n![b](b.png)"),
            mapOf("b.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "B" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun referencedImageFoundByBasenameFallbackIsStaged() = runBlocking {
        // R14-F1: the pre-scan collects basenames as well as exact
        // targets — a reference to `pic.png` from `notes/a.md` must
        // still stage `images/pic.png`, which the resolver finds by
        // its unique-basename fallback. A pre-scan that collected
        // exact targets only would leave this reference unresolved.
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf("notes/a.md" to "# A\n\n![a](pic.png)"),
            mapOf("images/pic.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(StagedImportFile("a.zip", zip, 1_700_000_000_000L)),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "A" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun txtImageSyntaxDoesNotConsumeSharedBudget() = runBlocking {
        // R14-F1: a `.txt` member goes through PlainTextCodec, which
        // never resolves images — so the pre-scan must not stage the
        // image its text merely mentions. With a 100-byte budget, an
        // image staged for the .txt note would leave the .md note's
        // referenced image unstaged.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf("a.txt" to "![a](a.png)"),
            mapOf("a.png" to pngBytes),
        )
        val zipB = zipOf(
            linkedMapOf("b.md" to "# B\n\n![b](b.png)"),
            mapOf("b.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "B" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun codeOnlyImageRefsDoNotConsumeSharedBudget() = runBlocking {
        // R18-F2: an image reference inside a code span or a fenced
        // block is never embedded — the converter carves code spans
        // out and treats fenced blocks as literal — so the pre-scan
        // must not stage it against the shared extracted-image
        // budget. ZIP A references its images only from code (one
        // code span, one fenced block); ZIP B references its image
        // for real. Each 1x1 PNG is 70 bytes; a 100-byte budget fits
        // one image. Under the old shape A's first code-only image
        // took 70 bytes and B's real image stayed unresolved.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf(
                "a.md" to "# A\n\n`![a](a1.png)`\n",
                "b.md" to "# B\n\n```\n![b](a2.png)\n```\n",
            ),
            mapOf("a1.png" to pngBytes, "a2.png" to pngBytes),
        )
        val zipB = zipOf(
            linkedMapOf("c.md" to "# C\n\n![c](c.png)"),
            mapOf("c.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(3, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        // The code-only references stayed literal text, never embeds.
        assertTrue(notes.first { it.title == "A" }.contentHtml.contains("![a](a1.png)"))
        assertFalse(notes.first { it.title == "A" }.contentHtml.contains("nota-attachment://"))
        assertTrue(notes.first { it.title == "B" }.contentHtml.contains("![b](a2.png)"))
        assertFalse(notes.first { it.title == "B" }.contentHtml.contains("nota-attachment://"))
        // And the budget was left whole for the real reference.
        assertTrue(notes.first { it.title == "C" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun frontMatterImageRefsDoNotConsumeSharedBudget() = runBlocking {
        // R23-F2: front matter is metadata — parseFrontMatter strips
        // it before rendering — so an image reference inside a
        // front-matter field is never embedded and must not be staged
        // against the shared extracted-image budget. ZIP A references
        // its image only from front matter; ZIP B references its
        // image for real. Each 1x1 PNG is 70 bytes; a 100-byte budget
        // fits one image. Under the old shape A's front-matter image
        // took 70 bytes and B's real image stayed unresolved.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf("a.md" to "---\ntitle: A\ncover: ![a](a.png)\n---\n\nBody text.\n"),
            mapOf("a.png" to pngBytes),
        )
        val zipB = zipOf(
            linkedMapOf("b.md" to "# B\n\n![b](b.png)"),
            mapOf("b.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
            imageBudget = ExtractedImageBudget(totalBytes = 100),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        // The front-matter reference never rendered, so no embed and
        // no unresolved count for it — and no trace of it in the note.
        assertFalse(notes.first { it.title == "A" }.contentHtml.contains("nota-attachment://"))
        assertFalse(notes.first { it.title == "A" }.contentHtml.contains("a.png"))
        // And the budget was left whole for the real reference.
        assertTrue(notes.first { it.title == "B" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun imageRefPreScanBoundLeavesLateRefsUnresolved() = runBlocking {
        // R20-F1: the pre-scan retained every distinct referenced
        // target and basename in two uncapped HashSets. The scan's
        // inputs are bounded only by the archive quota and the
        // per-member text bound, and distinct references are cheap
        // to mint, so a crafted archive could grow both sets to
        // millions of entries and OOM before any later budget check
        // runs. Past the retained-reference bound a reference is
        // simply not collected: its image is not staged, the
        // reference stays unresolved (counted), and the note still
        // imports. 80 members × 1,000 distinct flood references ≈
        // 80K references — past both the count bound and the
        // retained-chars bound — spread across members so each note
        // still converts. The first member's real reference is
        // inside the bound and embeds; the last member's is outside
        // it, so late.png (present in the archive) is never staged.
        // Under the old shape late.png staged and embedded:
        // 2 attachments and one fewer unresolved reference.
        val stack = newStack()
        val memberCount = 80
        val refsPerMember = 1_000
        val members = linkedMapOf<String, String>()
        var refIndex = 0
        for (m in 0 until memberCount) {
            val body = StringBuilder()
            if (m == 0) body.append("![real](real.png)\n\n")
            if (m == memberCount - 1) body.append("![late](late.png)\n\n")
            repeat(refsPerMember) {
                body.append("![](f").append(refIndex++).append(".png) ")
            }
            members["m$m.md"] = "# M$m\n\n$body"
        }
        val zip = zipOf(
            members,
            mapOf("real.png" to pngBytes, "late.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(StagedImportFile("flood.zip", zip, 1_700_000_000_000L)),
        )
        assertEquals(memberCount, outcome.report.imported)
        // Every flood reference is unresolved (its image does not
        // exist) and the late reference is unresolved too — the
        // bound kept it out of the pre-scan. Only real.png resolved.
        val totalRefs = memberCount * refsPerMember + 2
        assertEquals(totalRefs - 1, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "M0" }.contentHtml.contains("nota-attachment://"))
        assertFalse(
            notes.first { it.title == "M${memberCount - 1}" }.contentHtml
                .contains("nota-attachment://"),
        )
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun markdownSelectionEnforcesAggregateBudgetAcrossZips() = runBlocking {
        // R12-F1: each ZIP's own 16M-char budget starts fresh, so
        // several small ZIPs in one selection could accumulate far
        // past the budget before the post-loop check trims them.
        // Two ZIPs of sixty 200K-char notes each total ~24M chars;
        // the merged result is trimmed after each absorb, so the
        // second ZIP's tail notes are skipped with the budget reason
        // and the merged result never holds more than the budget.
        // 83 notes × ~200,007 chars ≈ 16.6M chars fit; the 37 tail
        // notes of the second ZIP do not.
        val stack = newStack()
        val zipA = zipOf(
            (0 until 60).associate { "a-$it.md" to "# A$it\n\n" + "x".repeat(200_000) },
        )
        val zipB = zipOf(
            (0 until 60).associate { "b-$it.md" to "# B$it\n\n" + "x".repeat(200_000) },
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 1_700_000_000_000L),
                StagedImportFile("b.zip", zipB, 1_700_000_000_001L),
            ),
        )
        assertEquals(83, outcome.report.imported)
        assertEquals(0, outcome.report.failed)
        assertTrue(outcome.parse.retainedTextChars <= ImportCaps.MAX_AGGREGATE_TEXT_CHARS)
        assertEquals(
            (23 until 60).map {
                SkippedImportFile("b-$it.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)
            },
            outcome.parse.skippedFiles,
        )
        assertEquals(83, stack.db.noteDao().getAll().size)
    }

    @Test
    fun expandedNotePastRowBoundIsRolledBack() = runBlocking {
        // R12-F2: the 1 MB cap ran on the pre-expansion content,
        // but the saved embed carries a 100K-char alt — the expanded
        // row is past the bound (and past the ~2 MiB CursorWindow
        // once its plain-text projection is counted). The note must
        // be rolled back, reported as failed, and the run-end
        // rebuild must still succeed — an oversize row left behind
        // would fail getAll() and break the whole run. The small
        // note proves the run itself is intact.
        val stack = newStack()
        val imageFile = File.createTempFile("expand-pic", ".png")
            .apply { writeBytes(pngBytes) }
        val big = ImportedNote(
            title = "BigExpanded",
            contentHtml = "<p>" + "a".repeat(990_000) + " %%NOMA_IMPORT_IMAGE_0%%</p>",
            createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = null,
            images = listOf(
                ImportedImage(
                    "pic.png", "image/png", imageFile,
                    pngBytes.size.toLong(), "a".repeat(100_000),
                    "%%NOMA_IMPORT_IMAGE_0%%",
                ),
            ),
            sourceName = "big-expanded.md",
        )
        val small = ImportedNote(
            title = "Small", contentHtml = "<p>small</p>",
            createdAt = 1_700_000_010_000L, updatedAt = 1_700_000_010_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = null, images = emptyList(),
            sourceName = "small.md",
        )
        val result = stack.repo.importNotes(listOf(big, small))
        assertEquals(1, result.report.imported)
        assertEquals(0, result.report.duplicates)
        assertEquals(1, result.report.failed)
        assertEquals(listOf("big-expanded.md"), result.report.failedNames)
        assertEquals(listOf("Small"), stack.db.noteDao().getAll().map { it.title })
        assertEquals(0, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun interruptedNoteWriteLeavesNoPlaceholderTokens() = runBlocking {
        // (Codex R24-F1): the note row used to commit with its raw
        // %%NOMA_IMPORT_IMAGE_...%% tokens before the images were
        // saved, so a kill between the two transactions left a
        // committed body full of tokens. The row is now written once,
        // with its final content — so even a write interrupted by a
        // failed image leaves no token in the stored body, and the
        // attachment rows match the embeds exactly. (A process kill
        // itself is not simulable in-process; the structural
        // invariant — one atomic commit of final content — is what
        // these tests pin.)
        val stack = newStack()
        val imageFile = File.createTempFile("ok-pic", ".png")
            .apply { writeBytes(pngBytes) }
        val missing = File(stagingDir(), "gone.png")
        val note = ImportedNote(
            title = "TwoImages",
            contentHtml = "<p>%%NOMA_IMPORT_IMAGE_0%% and %%NOMA_IMPORT_IMAGE_1%%</p>",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            pinned = false,
            archived = false,
            trashedAt = null,
            tagNames = emptyList(),
            folderName = null,
            images = listOf(
                ImportedImage(
                    "pic.png", "image/png", imageFile,
                    pngBytes.size.toLong(), "a good image",
                    "%%NOMA_IMPORT_IMAGE_0%%",
                ),
                ImportedImage(
                    "gone.png", "image/png", missing,
                    123L, "a missing image",
                    "%%NOMA_IMPORT_IMAGE_1%%",
                ),
            ),
            sourceName = "two-images.md",
        )
        val result = stack.repo.importNotes(listOf(note))
        assertEquals(1, result.report.imported)
        assertEquals(1, result.imageSaveFailures)
        val stored = stack.db.noteDao().getAll().single()
        assertFalse(stored.contentHtml.contains("%%NOMA_IMPORT_IMAGE"))
        assertTrue(stored.contentHtml.contains("nota-attachment://"))
        assertTrue(stored.contentHtml.contains("a missing image"))
        val attachments = stack.db.attachmentDao().getAll()
        assertEquals(1, attachments.size)
        assertTrue(
            stored.contentHtml.contains("nota-attachment://${attachments.single().id}"),
        )
    }

    @Test
    fun oversizeNoteWithImagesLeavesNoOrphans() = runBlocking {
        // (Codex R24-F1/F3): the old two-transaction write created
        // the folder/tag rows — and committed the note row with its
        // tokens — before the post-expansion size check threw. The
        // catch removed the note row, but the folder/tag rows and
        // the staged image files survived as orphans. The write is
        // now one transaction after the size check, so a refused
        // note leaves no note row, no attachment rows or files, and
        // no orphan folder or tag. The small note proves the run
        // itself is intact.
        val stack = newStack()
        val imageFile = File.createTempFile("doomed-pic", ".png")
            .apply { writeBytes(pngBytes) }
        val big = ImportedNote(
            title = "BigDoomed",
            contentHtml = "<p>" + "a".repeat(990_000) + " %%NOMA_IMPORT_IMAGE_0%%</p>",
            createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = listOf("doomed-a", "doomed-b"), folderName = "Doomed",
            images = listOf(
                ImportedImage(
                    "pic.png", "image/png", imageFile,
                    pngBytes.size.toLong(), "a".repeat(100_000),
                    "%%NOMA_IMPORT_IMAGE_0%%",
                ),
            ),
            sourceName = "big-doomed.md",
        )
        val small = ImportedNote(
            title = "Small", contentHtml = "<p>small</p>",
            createdAt = 1_700_000_010_000L, updatedAt = 1_700_000_010_000L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = null, images = emptyList(),
            sourceName = "small.md",
        )
        val result = stack.repo.importNotes(listOf(big, small))
        assertEquals(1, result.report.imported)
        assertEquals(0, result.report.duplicates)
        assertEquals(1, result.report.failed)
        assertEquals(listOf("big-doomed.md"), result.report.failedNames)
        assertEquals(listOf("Small"), stack.db.noteDao().getAll().map { it.title })
        assertEquals(0, stack.db.attachmentDao().getAll().size)
        assertTrue(stack.db.folderDao().getAll().none { it.name == "Doomed" })
        assertTrue(
            stack.db.tagDao().getAllTags()
                .none { it.name == "doomed-a" || it.name == "doomed-b" },
        )
        val attachmentFiles = File(stack.context.filesDir, "attachments")
            .walkTopDown().filter { it.isFile }.toList()
        assertEquals(emptyList<File>(), attachmentFiles)
    }

    @Test
    fun timestampLessLooseFileReimportDedupes() = runBlocking {
        // R13-F1 end to end: a loose file with no timestamp (the
        // document provider omits COLUMN_LAST_MODIFIED) used to get a
        // fresh wall clock on every import, so reimporting it
        // duplicated the note. The fallback stamp is now a stable 0,
        // so the second import dedupes — while a timestamped file
        // still keeps its real stamp.
        val stack = newStack()
        val dir = stagingDir()
        val timeless = File(dir, "timeless.md").apply { writeText("# Timeless\n\nbody") }
        val stamped = File(dir, "stamped.md").apply { writeText("# Stamped\n\nbody") }
        val staged = listOf(
            StagedImportFile("timeless.md", timeless, 0L),
            StagedImportFile("stamped.md", stamped, 1_700_000_000_000L),
        )
        val first = stack.repo.importMarkdownSelection(staged)
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        val notes = stack.db.noteDao().getAll()
        assertEquals(0L, notes.first { it.title == "Timeless" }.createdAt)
        assertEquals(1_700_000_000_000L, notes.first { it.title == "Stamped" }.createdAt)

        val second = stack.repo.importMarkdownSelection(staged)
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun timestampLessKeepNoteReimportDedupes() = runBlocking {
        // R13-F1, Keep shape: a Keep JSON with neither timestamp used
        // to get the wall clock, so reimporting it duplicated the
        // note. The fallback stamp is now a stable 0.
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/timeless.json" to """{"title":"Timeless","textContent":"body"}""",
            ),
        )
        val first = stack.repo.importKeepZip(zip)
        assertEquals(1, first.report.imported)
        assertEquals(0L, stack.db.noteDao().getAll().single().createdAt)
        val second = stack.repo.importKeepZip(zip)
        assertEquals(0, second.report.imported)
        assertEquals(1, second.report.duplicates)
        assertEquals(1, stack.db.noteDao().getAll().size)
    }

    @Test
    fun unknownDateNotesWithSameTitleInDifferentFoldersBothImport() = runBlocking {
        // R21-F1: two files, same title, no dates, different folders —
        // the old (title, createdAt) key collapsed them into one and
        // the second note was lost. The folder is part of the
        // unknown-date key now, so both land — and reimporting the
        // same two files still dedupes to zero new notes.
        val stack = newStack()
        fun note(folder: String, body: String) = ImportedNote(
            title = "meeting",
            contentHtml = "<p>$body</p>",
            createdAt = 0L, updatedAt = 0L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = folder, images = emptyList(),
            sourceName = "meeting.md",
        )
        val first = stack.repo.importNotes(listOf(note("A", "alpha"), note("B", "beta")))
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        val notes = stack.db.noteDao().getAll()
        assertEquals(2, notes.size)
        val folderNameById = stack.db.folderDao().getAll().associate { it.id to it.name }
        assertEquals(setOf("A", "B"), notes.map { folderNameById[it.folderId] }.toSet())
        assertEquals(
            setOf("<p>alpha</p>", "<p>beta</p>"),
            notes.map { it.contentHtml }.toSet(),
        )

        val second = stack.repo.importNotes(listOf(note("A", "alpha"), note("B", "beta")))
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun unknownDateNotesWithSameTitleInSameFolderBothImport() = runBlocking {
        // R21-F1, run half: same folder, same title, no dates — the
        // source file name is what keeps them apart within a run.
        // (Reimport still dedupes: the seeded key matches on
        // (title, folder).)
        val stack = newStack()
        fun note(source: String, body: String) = ImportedNote(
            title = "meeting",
            contentHtml = "<p>$body</p>",
            createdAt = 0L, updatedAt = 0L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = "A", images = emptyList(),
            sourceName = source,
        )
        val first = stack.repo.importNotes(listOf(note("x.md", "alpha"), note("y.md", "beta")))
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)

        val second = stack.repo.importNotes(listOf(note("x.md", "alpha"), note("y.md", "beta")))
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun sameNameDatelessLooseFilesFromDifferentFoldersBothImport() = runBlocking {
        // R23-F1: two SAF picks from different folders share a
        // display name ("note.md") and a title, both dateless,
        // different contents — the display name alone is not an
        // identity, so the old (title, folder, sourceName) run key
        // collapsed them and the second was reported as a duplicate.
        // The run key now carries a content hash for loose picks,
        // so both land — and reimporting the same two still dedupes
        // to zero new notes (the seeded (title, folder) key matches
        // both).
        val stack = newStack()
        val dirA = stagingDir()
        val dirB = stagingDir()
        val a = File(dirA, "note.md").apply { writeText("# Shared\n\nalpha") }
        val b = File(dirB, "note.md").apply { writeText("# Shared\n\nbeta") }
        val staged = listOf(
            StagedImportFile("note.md", a, 0L),
            StagedImportFile("note.md", b, 0L),
        )
        val first = stack.repo.importMarkdownSelection(staged)
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        val notes = stack.db.noteDao().getAll()
        assertEquals(2, notes.size)
        assertEquals(0L, notes[0].createdAt)
        assertEquals(0L, notes[1].createdAt)
        assertEquals(setOf("<p>alpha</p>", "<p>beta</p>"), notes.map { it.contentHtml }.toSet())

        val second = stack.repo.importMarkdownSelection(staged)
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun sameNameDatelessLooseFilesWithIdenticalContentDedupe() = runBlocking {
        // R23-F1, the other side of the hash identity: two picks
        // with the same name, title and contents ARE the same note,
        // so the second is a duplicate — the hash only keeps apart
        // notes whose contents differ.
        val stack = newStack()
        val dirA = stagingDir()
        val dirB = stagingDir()
        val a = File(dirA, "note.md").apply { writeText("# Shared\n\nsame") }
        val b = File(dirB, "note.md").apply { writeText("# Shared\n\nsame") }
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("note.md", a, 0L),
                StagedImportFile("note.md", b, 0L),
            ),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(1, outcome.report.duplicates)
        assertEquals(1, stack.db.noteDao().getAll().size)
    }

    @Test
    fun unknownDateNoteReimportDedupesAcrossFolderCase() = runBlocking {
        // R26-F1: the library already has a "notes" folder; a
        // dateless note imported from parent "Notes" resolves into
        // it (folder resolution is case-insensitive), but the seed
        // key used the raw folder name, so reimport missed the
        // stored note and inserted a duplicate. The folder
        // component is normalized now, so the reimport dedupes —
        // and only one folder exists, still named "notes".
        val stack = newStack()
        stack.repo.importNotes(
            listOf(
                ImportedNote(
                    title = "Seed", contentHtml = "<p>seed</p>",
                    createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                    pinned = false, archived = false, trashedAt = null,
                    tagNames = emptyList(), folderName = "notes", images = emptyList(),
                    sourceName = "seed.md",
                ),
            ),
        )
        assertEquals(listOf("notes"), stack.db.folderDao().getAll().map { it.name })
        fun note() = ImportedNote(
            title = "meeting", contentHtml = "<p>body</p>",
            createdAt = 0L, updatedAt = 0L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = "Notes", images = emptyList(),
            sourceName = "meeting.md",
        )
        val first = stack.repo.importNotes(listOf(note()))
        assertEquals(1, first.report.imported)
        assertEquals(0, first.report.duplicates)
        assertEquals(listOf("notes"), stack.db.folderDao().getAll().map { it.name })

        val second = stack.repo.importNotes(listOf(note()))
        assertEquals(0, second.report.imported)
        assertEquals(1, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun unknownDateNotesWithSameTitleInCaseVariantFoldersBothImport() = runBlocking {
        // R26-F1, run half: "Notes/x.md" and "notes/y.md" resolve
        // to the same folder, but the run key used the raw folder
        // name — the folder component is normalized now, and the
        // source names still keep them apart, so both land.
        val stack = newStack()
        fun note(source: String, folder: String, body: String) = ImportedNote(
            title = "meeting",
            contentHtml = "<p>$body</p>",
            createdAt = 0L, updatedAt = 0L,
            pinned = false, archived = false, trashedAt = null,
            tagNames = emptyList(), folderName = folder, images = emptyList(),
            sourceName = source,
        )
        val first = stack.repo.importNotes(
            listOf(note("x.md", "Notes", "alpha"), note("y.md", "notes", "beta")),
        )
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
        assertEquals(listOf("Notes"), stack.db.folderDao().getAll().map { it.name })
        assertEquals(
            setOf("<p>alpha</p>", "<p>beta</p>"),
            stack.db.noteDao().getAll().map { it.contentHtml }.toSet(),
        )
    }

    @Test
    fun datelessZipMembersWithSameBasenameInDifferentParentsBothImport() = runBlocking {
        // R26-F2: home/notes/meeting.md and work/notes/meeting.md
        // share a basename and an immediate folder; the run key
        // used the basename alone, so the second was a duplicate
        // even though its body differs. The identity now carries
        // the full entry path (plus a content hash), so both land
        // — and reimporting the same ZIP still dedupes to zero new
        // notes.
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf(
                "home/notes/meeting.md" to "# Meeting\n\nalpha",
                "work/notes/meeting.md" to "# Meeting\n\nbeta",
            ),
            dateless = true,
        )
        val first = stack.repo.importMarkdownZip(zip)
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        val notes = stack.db.noteDao().getAll()
        assertEquals(2, notes.size)
        assertEquals(0L, notes[0].createdAt)
        assertEquals(0L, notes[1].createdAt)
        assertEquals(setOf("<p>alpha</p>", "<p>beta</p>"), notes.map { it.contentHtml }.toSet())

        val second = stack.repo.importMarkdownZip(zip)
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun datelessZipMembersWithSamePathInDifferentZipsBothImport() = runBlocking {
        // R26-F2, across archives: the same entry path in two ZIPs
        // of one selection is two different sources when the
        // contents differ — the path alone would still collapse
        // them, so the identity also carries a content hash.
        val stack = newStack()
        val zipA = zipOf(
            linkedMapOf("notes/meeting.md" to "# Meeting\n\nalpha"),
            dateless = true,
        )
        val zipB = zipOf(
            linkedMapOf("notes/meeting.md" to "# Meeting\n\nbeta"),
            dateless = true,
        )
        val first = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 0L),
                StagedImportFile("b.zip", zipB, 0L),
            ),
        )
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
        assertEquals(
            setOf("<p>alpha</p>", "<p>beta</p>"),
            stack.db.noteDao().getAll().map { it.contentHtml }.toSet(),
        )

        val second = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("a.zip", zipA, 0L),
                StagedImportFile("b.zip", zipB, 0L),
            ),
        )
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun repeatedKeepImageReferenceWritesOneAttachment() = runBlocking {
        // R26-F3: one Keep JSON repeating the same valid image path
        // five times used to write five attachment rows — the
        // engine writes one row per spec before the size check,
        // while staging only ever held one file per path. The
        // parser dedupes specs per resolved entry now, so the note
        // imports with exactly one attachment and one embed.
        val stack = newStack()
        val repeated = (1..5).joinToString(",") {
            """{"filePath":"Takeout/Keep/pic.png","mimetype":"image/png"}"""
        }
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/repeats.json" to
                    """{"title":"Repeats","textContent":"look","attachments":[$repeated],"createdTimestampUsec":1700000000000000}""",
            ),
            mapOf("Takeout/Keep/pic.png" to pngBytes),
        )
        val outcome = stack.repo.importKeepZip(zip)
        assertEquals(1, outcome.report.imported)
        assertEquals(0, outcome.report.duplicates)
        assertEquals(0, outcome.report.failed)
        assertEquals(1, stack.db.attachmentDao().getAll().size)
        val note = stack.db.noteDao().getAll().single()
        assertEquals(1, "<img".toRegex().findAll(note.contentHtml).count())
    }

    @Test
    fun literalImageTokenTextSurvivesImport() = runBlocking {
        // R21-F2: a literal %%NOMA_IMPORT_IMAGE_0%% in the source used
        // to become an <img> at write time, because the engine
        // replaced EVERY occurrence of the fixed token. The emitted
        // token is salted per file now, and only its one occurrence is
        // replaced — so the literal stays literal, in prose and in a
        // code span, while the real image still embeds exactly once.
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf(
                "with-image.md" to "# TokenWithImage\n\nThe literal %%NOMA_IMPORT_IMAGE_0%% stays as text.\n\n`%%NOMA_IMPORT_IMAGE_0%%` in code stays too.\n\n![real pic](pic.png)\n",
                "without-image.md" to "# TokenAlone\n\nJust the literal %%NOMA_IMPORT_IMAGE_0%% here.\n",
            ),
            mapOf("pic.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(StagedImportFile("tokens.zip", zip, 1_700_000_000_000L)),
        )
        assertEquals(2, outcome.report.imported)
        assertEquals(0, outcome.report.duplicates)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        val withImage = notes.first { it.title == "TokenWithImage" }
        val alone = notes.first { it.title == "TokenAlone" }
        // The literal survives twice in the first note (prose + code
        // span) and once in the second — and the real image embedded
        // exactly once.
        assertEquals(2, countOccurrences(withImage.contentHtml, "%%NOMA_IMPORT_IMAGE_0%%"))
        assertEquals(1, countOccurrences(withImage.contentHtml, "nota-attachment://"))
        assertEquals(1, countOccurrences(alone.contentHtml, "%%NOMA_IMPORT_IMAGE_0%%"))
        assertEquals(0, countOccurrences(alone.contentHtml, "nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun duplicateZipImagePathsStayUnresolved() = runBlocking {
        // R13-F2: two entries sharing one image path are ambiguous —
        // the note embeds neither payload, the reference is counted,
        // and a uniquely pathed image in the same ZIP still embeds.
        // Before the fix the second entry silently overwrote the
        // first in the staging map and the note embedded whichever
        // payload came last.
        val stack = newStack()
        val zip = zipWithDuplicateEntries(
            listOf(
                "note.md" to "# Pic\n\n![dup](img/dup.png)\n\n![ok](img/ok.png)"
                    .toByteArray(Charsets.UTF_8),
                "img/dup.png" to pngBytes,
                "img/dup.png" to byteArrayOf(9, 9, 9),
                "img/ok.png" to pngBytes,
            ),
        )
        try {
            val outcome = stack.repo.importMarkdownSelection(
                listOf(StagedImportFile("dup.zip", zip, 1_700_000_000_000L)),
            )
            assertEquals(1, outcome.report.imported)
            assertEquals(1, outcome.parse.imagesUnresolved)
            val note = stack.db.noteDao().getAll().single()
            assertTrue(note.contentHtml.contains("nota-attachment://")) // the ok image
            assertTrue(note.contentHtml.contains("dup")) // alt kept, not embedded
            assertEquals(1, stack.db.attachmentDao().getAll().size)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun walkTripsWhenEntryNamesExceedBudget() {
        // R16-F1: per-walk name tracking was unbounded — a
        // quota-compliant archive of long-named, small-payload entries
        // could retain hundreds of MiB of names as Java strings before
        // any truncation warning. The walk now trips the same 8 MiB
        // budget as the inventory (a hard quota, like the entry-count
        // and byte quotas) instead of retaining them.
        // (~150 entries at ~60 KiB each clear the budget.)
        val entries = LinkedHashMap<String, String>()
        repeat(150) { i ->
            entries["notes/filler-$i-" + "x".repeat(59_900) + ".md"] = "# Filler $i\n\nbody"
        }
        val zip = zipOf(entries)
        try {
            val thrown = assertThrows(ImportZipException::class.java) {
                ZipImportReader(zip).walk { }
            }
            assertEquals(
                "That ZIP has too many or too long entry names to import safely.",
                thrown.message,
            )
        } finally {
            zip.delete()
        }
    }

    @Test
    fun longNamedDuplicateImagePathStaysUnresolved() = runBlocking {
        // R16-F1, R13-F2 contract under the new shape: a duplicated
        // path is still detected — and never staged — even when the
        // name itself is very long. The note embeds neither payload,
        // the reference is counted, and a uniquely pathed image in
        // the same ZIP still embeds.
        val longName = "img/" + "d".repeat(59_900) + ".png"
        val stack = newStack()
        val zip = zipWithDuplicateEntries(
            listOf(
                "note.md" to "# Pic\n\n![dup]($longName)\n\n![ok](img/ok.png)"
                    .toByteArray(Charsets.UTF_8),
                longName to pngBytes,
                longName to byteArrayOf(9, 9, 9),
                "img/ok.png" to pngBytes,
            ),
        )
        try {
            val outcome = stack.repo.importMarkdownSelection(
                listOf(StagedImportFile("dup.zip", zip, 1_700_000_000_000L)),
            )
            assertEquals(1, outcome.report.imported)
            assertEquals(1, outcome.parse.imagesUnresolved)
            val note = stack.db.noteDao().getAll().single()
            assertTrue(note.contentHtml.contains("nota-attachment://")) // the ok image
            assertTrue(note.contentHtml.contains("dup")) // alt kept, not embedded
            assertEquals(1, stack.db.attachmentDao().getAll().size)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun duplicateKeepAttachmentPathsStayUnresolved() {
        // R13-F2, Keep shape: two entries sharing one attachment path
        // are ambiguous — the note embeds neither payload, the
        // attachment is counted, and a uniquely pathed attachment in
        // the same archive still resolves.
        val zip = zipWithDuplicateEntries(
            listOf(
                "Takeout/Keep/dup.json" to
                    """{"title":"Dup","textContent":"see","attachments":[{"filePath":"Takeout/Keep/pic.png","mimetype":"image/png"}],"createdTimestampUsec":1700000000000000}"""
                        .toByteArray(Charsets.UTF_8),
                "Takeout/Keep/unique.json" to
                    """{"title":"Unique","textContent":"see2","attachments":[{"filePath":"Takeout/Keep/unique.png","mimetype":"image/png"}],"createdTimestampUsec":1700000100000000}"""
                        .toByteArray(Charsets.UTF_8),
                "Takeout/Keep/pic.png" to pngBytes,
                "Takeout/Keep/pic.png" to byteArrayOf(9, 9, 9),
                "Takeout/Keep/unique.png" to pngBytes,
            ),
        )
        try {
            val result = KeepTakeoutParser.parse(zip, stagingDir())
            assertEquals(2, result.notes.size)
            assertEquals(1, result.attachmentsSkipped)
            val dup = result.notes.first { it.title == "Dup" }
            assertEquals(0, dup.images.size)
            assertEquals("<p>see</p><p>pic.png</p>", dup.contentHtml)
            val unique = result.notes.first { it.title == "Unique" }
            assertEquals(1, unique.images.size)
            assertEquals("unique.png", unique.images[0].name)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun markdownZipNoteEmbedsSeparatelyPickedImage() = runBlocking {
        // R22-F1: a ZIP note referencing an image picked separately
        // from the ZIP embeds it — before the fix the ZIP built its
        // resolver from the archive alone, so the reference stayed
        // unresolved (alt text, counted) even though the user picked
        // the image for exactly that.
        val stack = newStack()
        val dir = stagingDir()
        val photo = File(dir, "photo.png").apply { writeBytes(pngBytes) }
        val zip = zipOf(
            linkedMapOf("note.md" to "# Note\n\n![p](photo.png)"),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("notes.zip", zip, 1_700_000_000_000L),
                StagedImportFile("photo.png", photo, 1_700_000_000_000L),
            ),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertTrue(notes.first { it.title == "Note" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

    @Test
    fun markdownZipNotePrefersInZipImageOverPickedImage() = runBlocking {
        // R22-F1: when the same name exists both in the archive and
        // among the picked images, the archive image wins. The two
        // candidates differ in size — the archive holds the proven
        // 1×1 PNG, the picked file a 2×2 red PNG built byte-by-byte —
        // so the winner is observable in the stored attachment's
        // dimensions. The picked file is never read when the archive
        // wins, so the test does not depend on decoding it.
        val stack = newStack()
        val dir = stagingDir()
        val picked = File(dir, "pic.png").apply { writeBytes(redPng2x2()) }
        val zip = zipOf(
            linkedMapOf("note.md" to "# Note\n\n![p](pic.png)"),
            mapOf("pic.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("notes.zip", zip, 1_700_000_000_000L),
                StagedImportFile("pic.png", picked, 1_700_000_000_000L),
            ),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(0, outcome.parse.imagesUnresolved)
        val attachments = stack.db.attachmentDao().getAll()
        assertEquals(1, attachments.size)
        assertEquals(1, attachments[0].widthPx)
        assertEquals(1, attachments[0].heightPx)
    }

    @Test
    fun ambiguousZipImageNameStaysUnresolvedDespitePickedImage() = runBlocking {
        // R22-F1: a name duplicated inside the archive stays
        // unresolved even when the selection holds a uniquely named
        // picked image — the ambiguity is about the name, and falling
        // through to the picked file would silently embed a guess.
        val stack = newStack()
        val dir = stagingDir()
        val picked = File(dir, "pic.png").apply { writeBytes(pngBytes) }
        val zip = zipOf(
            linkedMapOf("note.md" to "# Note\n\n![p](pic.png)"),
            mapOf("a/pic.png" to pngBytes, "b/pic.png" to pngBytes),
        )
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("notes.zip", zip, 1_700_000_000_000L),
                StagedImportFile("pic.png", picked, 1_700_000_000_000L),
            ),
        )
        assertEquals(1, outcome.report.imported)
        assertEquals(1, outcome.parse.imagesUnresolved)
        val notes = stack.db.noteDao().getAll()
        assertEquals("<p>p</p>", notes.first { it.title == "Note" }.contentHtml)
        assertEquals(0, stack.db.attachmentDao().getAll().size)
    }

    /** A 2×2 red PNG, built byte-by-byte: Bitmap is unproven under
     *  this Robolectric harness, and the test needs an image whose
     *  dimensions differ from [pngBytes] (1×1) so the winner is
     *  observable in the stored attachment's widthPx/heightPx. */
    private fun redPng2x2(): ByteArray {
        fun be32(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            return be32(data.size) + typeBytes + data + be32(crc.value.toInt())
        }
        val raw = ByteArrayOutputStream().apply {
            repeat(2) {
                write(0) // filter: none
                repeat(2) { write(byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte())) } // red RGBA
            }
        }.toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArray(64)
        val compressedLen = deflater.deflate(compressed)
        deflater.end()
        val ihdr = ByteArrayOutputStream().apply {
            write(be32(2))
            write(be32(2))
            write(byteArrayOf(8, 6, 0, 0, 0)) // 8-bit RGBA, no compression/filter/interlace
        }.toByteArray()
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        return signature + chunk("IHDR", ihdr) +
            chunk("IDAT", compressed.copyOf(compressedLen)) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun pastCapImageRefsDoNotStarveLaterZipImages() {
        // R27-F1: walk 2 used to collect image refs from every
        // member, including members walk 4 skips at the 5,000-note
        // cap, and walk 3 staged their images against the shared
        // selection budget — starving a later ZIP's importable
        // notes. A selection is emulated at parse level: two
        // parseImportZip calls sharing one small budget. The big ZIP
        // fills the note cap with plain notes and references 100
        // distinct images only past it; the small ZIP holds one note
        // referencing one image. The small note must import WITH its
        // image embedded — before the fix its image could not stage
        // (the budget was spent on past-cap images) and the
        // reference stayed unresolved.
        val budget = ExtractedImageBudget(totalBytes = 1_000)
        val entries = LinkedHashMap<String, String>()
        repeat(5_000) { i -> entries["note-$i.md"] = "# Note $i\n\nBody $i\n" }
        repeat(100) { i -> entries["past-$i.md"] = "# Past $i\n\n![img](img-past-$i.png)\n" }
        val binaries = (0 until 100).associate { "img-past-$it.png" to pngBytes }
        val big = MarkdownImporter.parseImportZip(
            zipOf(entries, binaries), stagingDir(), budget,
        )
        assertEquals(5_000, big.notes.size)
        assertEquals(100, big.skippedFiles.count { it.reason.contains("5,000 notes") })
        // The past-cap images were never staged, so the budget is intact.
        assertEquals(1_000L, budget.remaining())

        val small = MarkdownImporter.parseImportZip(
            zipOf(
                linkedMapOf("small.md" to "# Small\n\n![img](small.png)\n"),
                mapOf("small.png" to pngBytes),
            ),
            stagingDir(), budget,
        )
        assertEquals(1, small.notes.size)
        assertEquals(1, small.notes[0].images.size)
        assertEquals(0, small.imagesUnresolved)
    }

    @Test
    fun crossArchiveNoteCapStopsPreScan() {
        // R27-F1, cross-archive half: the running total must travel
        // into each ZIP parse, so a second archive neither scans
        // refs for members past the run cap nor converts them. The
        // first archive contributes 4,999 notes; the second holds
        // two notes with images, of which only the first fits the
        // run cap. The budget fits exactly one image, so staging the
        // second note's image would be observable — it must never
        // be staged.
        val budget = ExtractedImageBudget(totalBytes = 140)
        val firstEntries = LinkedHashMap<String, String>()
        repeat(4_999) { i -> firstEntries["n-$i.md"] = "# N $i\n\nBody\n" }
        val first = MarkdownImporter.parseImportZip(
            zipOf(firstEntries), stagingDir(), budget,
        )
        assertEquals(4_999, first.notes.size)

        val second = MarkdownImporter.parseImportZip(
            zipOf(
                linkedMapOf(
                    "one.md" to "# One\n\n![img](one.png)\n",
                    "two.md" to "# Two\n\n![img](two.png)\n",
                ),
                mapOf("one.png" to pngBytes, "two.png" to pngBytes),
            ),
            stagingDir(), budget, null, 4_999,
        )
        assertEquals(1, second.notes.size)
        assertEquals("One", second.notes[0].title)
        assertEquals(1, second.notes[0].images.size)
        assertEquals(0, second.imagesUnresolved)
        assertEquals(1, second.skippedFiles.size)
        assertTrue(second.skippedFiles[0].reason.contains("5,000 notes"))
        // Only the first note's image was staged.
        assertEquals(140L - pngBytes.size, budget.remaining())
    }

    @Test
    fun keepHugeLabelsArrayIsBounded() = runBlocking {
        // R27-F2: labels accumulated unbounded while no text budget
        // counted them — a huge labels array with a tiny body
        // retained millions of strings. 150 labels now import with
        // the first 100 kept as tags; the note itself still imports.
        val stack = newStack()
        val labels = (0 until 150).joinToString(",") { """{"name":"label$it"}""" }
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/many.json" to
                    """{"title":"Many","textContent":"tiny","labels":[$labels],"createdTimestampUsec":1700000000000000}""",
            ),
        )
        val outcome = stack.repo.importKeepZip(zip)
        assertEquals(1, outcome.report.imported)
        assertEquals(ImportCaps.MAX_TAGS_PER_NOTE, stack.db.tagDao().getAllTags().size)
        val note = stack.db.noteDao().getAll().first { it.title == "Many" }
        assertEquals(
            ImportCaps.MAX_TAGS_PER_NOTE,
            stack.db.tagDao().observeAllCrossRefs().first().count { it.noteId == note.id },
        )
    }

    @Test
    fun markdownHugeFrontMatterTagListIsBounded() = runBlocking {
        // R27-F2, Markdown half: a front-matter tag list of 150 tags
        // imports with the first 100 kept as tags.
        val stack = newStack()
        val tags = (0 until 150).joinToString(", ") { "tag$it" }
        val zip = zipOf(
            linkedMapOf(
                "many.md" to "---\ntags: [$tags]\n---\n# Many\n\nbody",
            ),
        )
        val outcome = stack.repo.importMarkdownZip(zip)
        assertEquals(1, outcome.report.imported)
        assertEquals(ImportCaps.MAX_TAGS_PER_NOTE, stack.db.tagDao().getAllTags().size)
    }

    @Test
    fun overLongTagNameIsDropped() {
        // R27-F2: a single label/tag name past the per-name byte cap
        // is dropped, never truncated — the note keeps its other labels.
        val longName = "x".repeat(2_000)
        val keep = KeepTakeoutParser.parse(
            zipOf(
                linkedMapOf(
                    "Takeout/Keep/long.json" to
                        """{"title":"Long","textContent":"tiny","labels":[{"name":"$longName"},{"name":"kept"}],"createdTimestampUsec":1700000000000000}""",
                ),
            ),
            stagingDir(),
        )
        assertEquals(1, keep.notes.size)
        assertEquals(listOf("kept"), keep.notes[0].tagNames)

        val md = MarkdownImporter.parseImportZip(
            zipOf(
                linkedMapOf(
                    "long.md" to "---\ntags: [$longName, kept]\n---\n# Long\n\nbody",
                ),
            ),
            stagingDir(),
        )
        assertEquals(1, md.notes.size)
        assertEquals(listOf("kept"), md.notes[0].tagNames)
    }

    @Test
    fun rebuildLibraryIndexesSeesEditCommittedDuringRebuild() = runBlocking {
        // R28-F1: rebuildLibraryIndexes used to load its snapshot
        // OUTSIDE the rebuild transaction — an edit committing
        // between the load and the transaction left the note indexed
        // by its old content, and a note created in the window lost
        // its FTS row entirely (deleted, never re-added). The
        // snapshot now loads inside the transaction, so the rebuild
        // sees exactly the committed state: the live handle holds a
        // write transaction open with the edit + the new note inside
        // it, and the sibling handle's rebuild cannot begin its
        // transaction until that commits.
        val context = RuntimeEnvironment.getApplication()
        val accountId = "rebuild-race"
        context.deleteDatabase(NotaDatabase.databaseName(accountId))
        val dbLive = NotaDatabase.build(context, accountId)
        dbs += dbLive
        val dbSibling = NotaDatabase.build(context, accountId)
        dbs += dbSibling
        fun note(id: String, body: String) = NoteEntity(
            id = id,
            title = id,
            contentHtml = "<p>$body</p>",
            plainText = body,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
        )
        val alpha = note("a", "alpha")
        dbLive.noteDao().upsert(alpha)
        dbLive.refreshFtsEntry(alpha)
        assertEquals("alpha", ftsRows(dbLive)["a"])

        val txnOpen = CountDownLatch(1)
        val releaseTxn = CountDownLatch(1)
        val liveJob = async(Dispatchers.IO) {
            dbLive.withTransaction {
                dbLive.noteDao().upsert(note("a", "beta"))
                dbLive.refreshFtsEntry(note("a", "beta"))
                val gamma = note("c", "gamma")
                dbLive.noteDao().upsert(gamma)
                dbLive.refreshFtsEntry(gamma)
                txnOpen.countDown()
                releaseTxn.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(txnOpen.await(5, TimeUnit.SECONDS))
        val rebuildJob = async(Dispatchers.IO) { rebuildLibraryIndexes(dbSibling) }
        // Give the sibling time to reach its snapshot load (old
        // shape) or block on the write lock (new shape), then
        // release the live transaction and let both finish.
        delay(500)
        releaseTxn.countDown()
        liveJob.await()
        rebuildJob.await()
        val fts = ftsRows(dbLive)
        assertEquals("beta", fts["a"])
        assertEquals("gamma", fts["c"])
    }

    private fun ftsRows(db: NotaDatabase): Map<String, String> {
        val cursor = db.openHelper.writableDatabase.query("SELECT noteId, plainText FROM notes_fts")
        cursor.use {
            val out = mutableMapOf<String, String>()
            val idIdx = it.getColumnIndexOrThrow("noteId")
            val textIdx = it.getColumnIndexOrThrow("plainText")
            while (it.moveToNext()) out[it.getString(idIdx)] = it.getString(textIdx)
            return out
        }
    }

    @Test
    fun unknownDateKeepNotesWithSameBasenameInDifferentFoldersBothImport() = runBlocking {
        // R28-F2: KeepDraft carried only the basename as its
        // identity, so two nested Keep JSONs with the same basename
        // + title and no usable timestamps collapsed to a duplicate
        // despite different content. The run identity is now the
        // full entry path plus a content hash (R26-F2 shape), so
        // both land — and reimporting still dedupes to zero new
        // notes (the seeded key has no source identity: the R21
        // residual).
        val stack = newStack()
        val zip = zipOf(
            linkedMapOf(
                "Takeout/Keep/a/note.json" to """{"title":"Shared","textContent":"alpha"}""",
                "Takeout/Keep/b/note.json" to """{"title":"Shared","textContent":"beta"}""",
            ),
            dateless = true,
        )
        val first = stack.repo.importKeepZip(zip)
        assertEquals(2, first.report.imported)
        assertEquals(0, first.report.duplicates)
        val notes = stack.db.noteDao().getAll()
        assertEquals(2, notes.size)
        assertEquals(setOf("<p>alpha</p>", "<p>beta</p>"), notes.map { it.contentHtml }.toSet())
        val second = stack.repo.importKeepZip(zip)
        assertEquals(0, second.report.imported)
        assertEquals(2, second.report.duplicates)
        assertEquals(2, stack.db.noteDao().getAll().size)
    }

    @Test
    fun preScanDoesNotStageImagesForMembersPastRemainingTextBudget() {
        // R28-F4: the fix-30 pre-scan applied the note cap and the
        // per-note bound but read each member against the FULL
        // 16M-char cap — walk 4 + the merge refuse on the REMAINING
        // budget. The pre-scan now simulates the remaining budget
        // from the selection-level total, so a member past it never
        // stages its images against the shared budget. Each 1x1 PNG
        // is 70 bytes; a 100-byte budget fits one image.
        val dir = stagingDir()
        val bigBody = "word ".repeat(30_000) // 150,000 chars
        val zip = zipOf(
            linkedMapOf(
                "big.md" to "$bigBody\n\n![big](big.png)",
                "small.md" to "# Small\n\n![s](small.png)",
            ),
            mapOf("big.png" to pngBytes, "small.png" to pngBytes),
        )
        val budget = ExtractedImageBudget(totalBytes = 100)
        val result = MarkdownImporter.parseImportZip(
            zip, dir, budget,
            alreadyRetainedTextChars = 16_700_000L,
        )
        // The small note's image staged (70 of 100 bytes); the big
        // member's image never did — the budget proves it.
        assertEquals(30L, budget.remaining())
        assertEquals(setOf("small.png"), dir.listFiles()?.map { it.name }?.toSet())
        assertEquals(1, result.imagesUnresolved) // big.md's ref, unstaged
        val small = result.notes.first { it.title == "Small" }
        assertEquals(1, small.images.size)
        val big = result.notes.first { it.title == "big" }
        assertEquals(0, big.images.size)
    }

    @Test
    fun remainingTextBudgetSimulatedAcrossZipsInOneSelection() = runBlocking {
        // R28-F4, selection level: the first ZIP consumes most of
        // the aggregate budget; the second ZIP's over-budget member
        // must not stage its image, and the third ZIP's importable
        // note keeps its image. '&' expands 5x through the
        // escape-first converter, so 17 near-1M notes cost ~3.2M
        // raw chars to build; each 1x1 PNG is 70 bytes and the
        // 100-byte shared budget fits one image.
        val stack = newStack()
        val ampNote = "&".repeat(190_000)
        val zip1Entries = LinkedHashMap<String, String>()
        repeat(17) { i -> zip1Entries["n%02d.md".format(i)] = ampNote }
        val zip1 = zipOf(zip1Entries)
        val zip2 = zipOf(
            linkedMapOf("big.md" to "word ".repeat(170_000) + "\n\n![big](big.png)"),
            mapOf("big.png" to pngBytes),
        )
        val zip3 = zipOf(
            linkedMapOf("small.md" to "# Small\n\n![s](s.png)"),
            mapOf("s.png" to pngBytes),
        )
        val budget = ExtractedImageBudget(totalBytes = 100)
        val outcome = stack.repo.importMarkdownSelection(
            listOf(
                StagedImportFile("one.zip", zip1, 1_700_000_000_000L),
                StagedImportFile("two.zip", zip2, 1_700_000_000_001L),
                StagedImportFile("three.zip", zip3, 1_700_000_000_002L),
            ),
            imageBudget = budget,
        )
        assertEquals(18, outcome.report.imported) // 17 + 0 + 1
        assertEquals(1, outcome.parse.imagesUnresolved) // big.md's ref
        assertEquals(30L, budget.remaining()) // only s.png staged
        assertTrue(
            outcome.parse.skippedFiles.any {
                it.name == "big.md" && it.reason == ImportCaps.AGGREGATE_TEXT_BUDGET_REASON
            },
        )
        val notes = stack.db.noteDao().getAll()
        assertEquals(18, notes.size)
        assertTrue(notes.first { it.title == "Small" }.contentHtml.contains("nota-attachment://"))
        assertEquals(1, stack.db.attachmentDao().getAll().size)
    }

}