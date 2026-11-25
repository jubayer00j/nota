package app.nota.data.importing

import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // each conversion. One member carries ~1 MiB of text.
        val zip = zipOf(
            linkedMapOf(
                "notes/one.md" to "# One\n\nfirst",
                "notes/two.md" to "# Two\n\n" + "x".repeat(1_000_000),
                "notes/three.md" to "# Three\n\n![p](pic.png)",
            ),
            mapOf("notes/pic.png" to pngBytes),
        )
        val result = MarkdownImporter.parseImportZip(zip, stagingDir())
        assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
        assertEquals(listOf("One", "Two", "Three"), result.notes.map { it.title })
        assertEquals("<p>first</p>", result.notes[0].contentHtml)
        assertTrue(result.notes[1].contentHtml.length > 1_000_000)
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
        // R3-F3: entry names past the 8 MiB retained-name budget are
        // dropped from the inventory, so a note whose name sits past
        // the cut is never enumerated — the outcome must say so
        // instead of silently importing nothing. (ZIP entry names top
        // out at 64 KiB, so ~150 entries at ~60 KiB each clear the
        // budget; the note JSON is written last, past the cut.)
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
                    "The archive's file-name list was truncated, so some notes may not have been imported.",
                ),
            ),
            result.skippedFiles,
        )
    }

    @Test
    fun markdownZipInventoryBudgetTruncatesLongNames() {
        // R4-F1: the walk-1 inventory is bounded by the same 8 MiB
        // retained-name budget as the reader — names past it are
        // dropped and the truncation is reported, never silent.
        // (~150 entries at ~60 KiB each clear the budget; the last
        // note sits past the cut.)
        val entries = LinkedHashMap<String, String>()
        repeat(150) { i ->
            entries["notes/filler-$i-" + "x".repeat(59_900) + ".md"] = "# Filler $i\n\nbody"
        }
        entries["notes/" + "z".repeat(59_900) + ".md"] = "# Hidden\n\nnever listed"
        val result = MarkdownImporter.parseImportZip(zipOf(entries), stagingDir())
        assertTrue(result.notes.size in 1 until 150)
        assertTrue(result.notes.none { it.title == "Hidden" })
        assertTrue(
            result.skippedFiles.contains(
                SkippedImportFile(
                    "ZIP archive",
                    "The archive's file-name list was truncated, so some notes may not have been imported.",
                ),
            ),
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
        // R6-F1: three 6M-char Keep notes total ~18M chars — past
        // the 16M aggregate budget. The third is reported as
        // skipped, never silently dropped; the first two keep their
        // content intact.
        val body = "y".repeat(6_000_000)
        val entries = linkedMapOf<String, String>()
        repeat(3) { i ->
            entries["Takeout/Keep/big-$i.json"] =
                """{"title":"Big$i","textContent":"$body","createdTimestampUsec":${1_700_000_000_000L + i}}"""
        }
        val result = KeepTakeoutParser.parse(zipOf(entries), stagingDir())
        assertEquals(2, result.notes.size)
        assertEquals(listOf("Big0", "Big1"), result.notes.map { it.title })
        assertTrue(result.notes.all { it.contentHtml.length > 6_000_000 })
        assertEquals(
            listOf(SkippedImportFile("big-2.json", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun markdownSelectionEnforcesAggregateTextBudget() = runBlocking {
        // R6-F1 (merge path): nine 2M-char loose files total ~18M
        // chars — past the 16M budget. Eight import; the ninth is
        // reported as skipped.
        val stack = newStack()
        val dir = stagingDir()
        val staged = (0 until 9).map { i ->
            val file = File(dir, "big-$i.md").apply { writeText("# Big$i\n\n" + "x".repeat(2_000_000)) }
            StagedImportFile("big-$i.md", file, 1_700_000_000_000L + i)
        }
        val outcome = stack.repo.importMarkdownSelection(staged)
        assertEquals(8, outcome.report.imported)
        assertEquals(0, outcome.report.failed)
        assertEquals(
            listOf(SkippedImportFile("big-8.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            outcome.parse.skippedFiles,
        )
        assertEquals(8, stack.db.noteDao().getAll().size)
    }

    @Test
    fun accountSwitchWaitsForHeldOffWrite() = runBlocking {
        // R6-F3: switchTo must block while a write holds the switch
        // off, and proceed once the write finishes — the
        // serialization the pinned-handle race needs.
        val context = RuntimeEnvironment.getApplication()
        val seq = ++stackSeq
        val startId = "import-lock-a-$seq"
        val otherId = "import-lock-b-$seq"
        context.deleteDatabase(NotaDatabase.databaseName(startId))
        context.deleteDatabase(NotaDatabase.databaseName(otherId))
        val provider = DatabaseProvider(context, startId)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                provider.withSwitchHeldOff {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            var switchReturned = false
            val switchThread = thread {
                provider.switchTo(otherId)
                switchReturned = true
            }
            delay(300)
            assertFalse(switchReturned) // still blocked on the held-off write
            release.complete(Unit)
            job.join()
            switchThread.join(5_000)
            assertTrue(switchReturned)
            assertEquals(otherId, provider.accountId.value)
        } finally {
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
}
