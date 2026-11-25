package app.nota.data.importing

import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown/text importer: a port of 1.x importers.ts, so these
 * expectations mirror 1.x's converter behaviour (escape-first inline
 * rendering, sequence-confirmed lettered lists, H3 clamp, front
 * matter) — with Nota's own task-list serialiser shape where 1.x used
 * Tiptap's `<label><input>` markup.
 */
class MarkdownImportTest {

    private fun parseOne(
        name: String,
        text: String,
        path: String = name,
        lastModified: Long = 1_700_000_000_000L,
        resolver: ImportImageResolver? = null,
    ): ImportParseResult = MarkdownImporter.parseMarkdownFiles(
        listOf(MarkdownFileInput(name, path, text, lastModified)),
        resolver,
    )

    @Test
    fun plainTextFileBecomesParagraphsWithFileStamps() {
        val result = parseOne("note.txt", "First line\nsecond line\n\nNew paragraph")
        assertEquals(1, result.notes.size)
        val note = result.notes[0]
        assertEquals("note", note.title)
        assertEquals("<p>First line<br>second line</p><p>New paragraph</p>", note.contentHtml)
        assertEquals(1_700_000_000_000L, note.createdAt)
        assertEquals(1_700_000_000_000L, note.updatedAt)
        assertEquals(null, note.folderName)
    }

    @Test
    fun textFileFolderComesFromPath() {
        val result = parseOne("note.txt", "body", path = "docs/note.txt")
        assertEquals("docs", result.notes[0].folderName)
    }

    @Test
    fun h1BecomesTitleAndInlineFormattingRenders() {
        val result = parseOne(
            "hello.md",
            "# My Title\n\nHello **bold** and *italic* and `code` and " +
                "[site](https://example.com) and [bad](javascript:alert(1)).",
        )
        val note = result.notes[0]
        assertEquals("My Title", note.title)
        // The unsafe link degrades to its label; the source's second
        // ")" is ordinary text (the link regex stops at the first).
        assertEquals(
            "<p>Hello <strong>bold</strong> and <em>italic</em> and <code>code</code> and " +
                "<a href=\"https://example.com\">site</a> and bad).</p>",
            note.contentHtml,
        )
    }

    @Test
    fun deepHeadingsClampToH3AndFilenameIsTheFallbackTitle() {
        val result = parseOne("deep.md", "#### Deep heading")
        assertEquals("deep", result.notes[0].title)
        assertEquals("<h3>Deep heading</h3>", result.notes[0].contentHtml)
    }

    @Test
    fun taskListUsesNotaSerialiserShape() {
        val result = parseOne("tasks.md", "- [ ] todo\n- [x] done")
        assertEquals(
            "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>todo</p></div></li>" +
                "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>done</p></div></li>" +
                "</ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun orderedListStartAndLetteredType() {
        val numbered = parseOne("n.md", "3. three\n4. four")
        assertEquals(
            "<ol start=\"3\"><li><p>three</p></li><li><p>four</p></li></ol>",
            numbered.notes[0].contentHtml,
        )
        val lettered = parseOne("l.md", "a. one\nb. two")
        assertEquals(
            "<ol type=\"a\"><li><p>one</p></li><li><p>two</p></li></ol>",
            lettered.notes[0].contentHtml,
        )
        val roman = parseOne("r.md", "i. one\nii. two")
        assertEquals(
            "<ol type=\"i\"><li><p>one</p></li><li><p>two</p></li></ol>",
            roman.notes[0].contentHtml,
        )
    }

    @Test
    fun loneLetteredLineStaysProse() {
        val result = parseOne("prose.md", "a. This is a sentence.\n\nAnother paragraph.")
        assertEquals(
            "<p>a. This is a sentence.</p><p>Another paragraph.</p>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun fencedCodeIsLiteralAndEscaped() {
        val result = parseOne("code.md", "```\nif (a < b) { **x** }\n```")
        assertEquals(
            "<pre><code>if (a &lt; b) { **x** }</code></pre>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun blockquoteSplitsParagraphsOnBlankQuoteLines() {
        val result = parseOne("q.md", "> hello\n> world\n>\n> second")
        assertEquals(
            "<blockquote><p>hello world</p><p>second</p></blockquote>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun nestedListsClampToOneLevel() {
        val result = parseOne("nested.md", "- a\n  - b\n    - c")
        assertEquals(
            "<ul><li><p>a</p><ul><li><p>b</p></li><li><p>c</p></li></ul></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun mixedMarkerNestingNestsByIndent() {
        // R3-F1: a numbered sub-list under a bullet parent nests inside
        // the parent item — 1.x flattens it into a sibling <ol>.
        val result = parseOne("mixed.md", "- Shopping\n  1. Milk\n  2. Eggs\n- Chores")
        assertEquals(
            "<ul><li><p>Shopping</p><ol><li><p>Milk</p></li><li><p>Eggs</p></li></ol></li>" +
                "<li><p>Chores</p></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun numberedParentWithBulletChildrenNests() {
        // R3-F1: the mirror case — bullets nest under a numbered parent.
        val result = parseOne("mixed2.md", "1. First\n   - sub a\n   - sub b\n2. Second")
        assertEquals(
            "<ol><li><p>First</p><ul><li><p>sub a</p></li><li><p>sub b</p></li></ul></li>" +
                "<li><p>Second</p></li></ol>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun letteredItemsNestUnderBulletParent() {
        // R3-F1: a sequence-confirmed lettered list nests too, keeping
        // its marker system.
        val result = parseOne("mixed3.md", "- Items\n  a. alpha\n  b. beta")
        assertEquals(
            "<ul><li><p>Items</p><ol type=\"a\"><li><p>alpha</p></li><li><p>beta</p></li></ol></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun topLevelKindChangeStillSplitsLists() {
        // R3-F1: nesting is by indent — a kind change AT the base
        // indent is still two sibling lists, as before.
        val result = parseOne("split.md", "1. one\n2. two\n- bullet")
        assertEquals(
            "<ol><li><p>one</p></li><li><p>two</p></li></ol>" +
                "<ul><li><p>bullet</p></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun taskParentWithOrderedChildrenKeepsNumbering() {
        // R3-F1: a nested ordered list under a task parent stays an
        // ordered list (its level decides its own kind).
        val result = parseOne("mixed4.md", "- [ ] Shopping\n  1. Milk")
        assertEquals(
            "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Shopping</p>" +
                "<ol><li><p>Milk</p></li></ol></div></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun orderedParentWithTaskChildrenKeepsCheckbox() {
        // R3-F1: a nested task under a numbered parent stays a task list.
        val result = parseOne("mixed5.md", "1. First\n   - [ ] sub")
        assertEquals(
            "<ol><li><p>First</p>" +
                "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>sub</p></div></li>" +
                "</ul></li></ol>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun nestedMixedRunsSplitByKind() {
        // R5-F3: nested children mixing markers split into kind-runs
        // at their own level — the numbered child is NOT pulled into
        // the bullet run the first child started.
        val result = parseOne("mixed6.md", "- Parent\n  - a\n  1. b")
        assertEquals(
            "<ul><li><p>Parent</p><ul><li><p>a</p></li></ul>" +
                "<ol><li><p>b</p></li></ol></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun nestedMixedRunsSplitByKindReversed() {
        // R5-F3: the mirror case — a numbered child first, then a
        // bullet child, each under its own list type.
        val result = parseOne("mixed7.md", "- Parent\n  1. a\n  - b")
        assertEquals(
            "<ul><li><p>Parent</p><ol><li><p>a</p></li></ol>" +
                "<ul><li><p>b</p></li></ul></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun rawHtmlIsEscapedNeverPassedThrough() {
        val result = parseOne("raw.md", "<script>alert(1)</script>")
        assertEquals(
            "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun strikethroughAndUnderscoreRules() {
        val result = parseOne("s.md", "~~gone~~ and snake_case stays")
        assertEquals("<p><s>gone</s> and snake_case stays</p>", result.notes[0].contentHtml)
    }

    @Test
    fun frontMatterTitleDateAndInlineTags() {
        val result = parseOne(
            "fm.md",
            "---\ntitle: FM Title\ndate: 2026-01-15\ntags: [alpha, beta]\n---\nBody text.",
        )
        val note = result.notes[0]
        assertEquals("FM Title", note.title)
        assertEquals(listOf("alpha", "beta"), note.tagNames)
        val expected = LocalDate.parse("2026-01-15")
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, note.createdAt)
        assertEquals("<p>Body text.</p>", note.contentHtml)
    }

    @Test
    fun frontMatterBlockListTags() {
        val result = parseOne("fm2.md", "---\ntags:\n  - one\n  - two\n---\nx")
        assertEquals(listOf("one", "two"), result.notes[0].tagNames)
    }

    @Test
    fun localImageResolvesToTokenAndRemoteImageKeepsAlt() {
        val imageFile = File.createTempFile("pic", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val resolver = createImportImageResolver(listOf("pic.png" to imageFile))
        val result = parseOne(
            "img.md",
            "![alt text](pic.png)\n\n![remote](https://example.com/a.png)",
            resolver = resolver,
        )
        val note = result.notes[0]
        assertEquals(1, note.images.size)
        assertEquals("%%NOMA_IMPORT_IMAGE_0%%", note.images[0].token)
        assertTrue(note.contentHtml.contains("%%NOMA_IMPORT_IMAGE_0%%"))
        assertTrue(note.contentHtml.contains("<p>remote</p>"))
        assertEquals(1, result.imagesUnresolved)
        imageFile.delete()
    }

    @Test
    fun unresolvedImageKeepsAltTextAndCounts() {
        val resolver = createImportImageResolver(emptyList())
        val result = parseOne("img2.md", "![missing alt](nope.png)", resolver = resolver)
        assertEquals("<p>missing alt</p>", result.notes[0].contentHtml)
        assertEquals(1, result.imagesUnresolved)
    }

    @Test
    fun resolverFallsBackToUniqueBasename() {
        val imageFile = File.createTempFile("shot", ".png").apply { writeBytes(byteArrayOf(9)) }
        val resolver = createImportImageResolver(listOf("elsewhere/shot.png" to imageFile))
        val resolved = resolver.resolve("docs/note.md", "shot.png")
        assertTrue(resolved != null)
        assertEquals("shot.png", resolved!!.name)
        imageFile.delete()
    }

    @Test
    fun resolverLeavesAmbiguousNamesUnresolved() {
        // R4-F2: two different files sharing a display name are
        // ambiguous — the reference stays unresolved instead of
        // silently embedding whichever was staged last. The same
        // staged file listed twice is one candidate, not an ambiguity,
        // and a unique full path still resolves.
        val first = File.createTempFile("amb-a", ".png").apply { writeBytes(byteArrayOf(1)) }
        val second = File.createTempFile("amb-b", ".png").apply { writeBytes(byteArrayOf(2)) }
        val ambiguous = createImportImageResolver(
            listOf("image.png" to first, "image.png" to second),
        )
        assertNull(ambiguous.resolve("note.md", "image.png"))
        val pathed = createImportImageResolver(
            listOf("a/image.png" to first, "b/image.png" to second),
        )
        assertNull(pathed.resolve("note.md", "image.png"))
        assertNotNull(pathed.resolve("note.md", "a/image.png"))
        val sameTwice = createImportImageResolver(
            listOf("image.png" to first, "image.png" to first),
        )
        assertNotNull(sameTwice.resolve("note.md", "image.png"))
        first.delete()
        second.delete()
    }

    @Test
    fun enforceNoteCapTruncatesMergedSourcesIntoSkippedFiles() {
        // Two capped sources merged into one run: 2,501 + 2,500 notes
        // must come out as exactly 5,000 notes, with the one excess
        // reported — never silently dropped.
        val result = ImportParseResult()
        val first = ImportParseResult()
        repeat(2_501) { i ->
            first.notes += ImportedNote(
                title = "First $i", contentHtml = "<p>x</p>",
                createdAt = 1_700_000_000_000L + i, updatedAt = 1_700_000_000_000L,
                pinned = false, archived = false, trashedAt = null,
                tagNames = emptyList(), folderName = null, images = emptyList(),
                sourceName = "first-$i.md",
            )
        }
        val second = ImportParseResult()
        repeat(2_500) { i ->
            second.notes += ImportedNote(
                title = "Second $i", contentHtml = "<p>x</p>",
                createdAt = 1_800_000_000_000L + i, updatedAt = 1_800_000_000_000L,
                pinned = false, archived = false, trashedAt = null,
                tagNames = emptyList(), folderName = null, images = emptyList(),
                sourceName = "second-$i.md",
            )
        }
        result.absorb(first)
        result.absorb(second)
        result.enforceNoteCap()
        assertEquals(5_000, result.notes.size)
        assertEquals(
            listOf(
                SkippedImportFile(
                    "second-2499.md",
                    "Import limit reached (5,000 notes per run).",
                ),
            ),
            result.skippedFiles,
        )
    }

    @Test
    fun normaliseImportPathResolvesDots() {
        assertEquals("a/c", normaliseImportPath("a/./b/../c"))
        assertEquals("x/y", normaliseImportPath("/x/y"))
        assertEquals("a/b", normaliseImportPath("a\\b"))
    }

    @Test
    fun parseMarkdownFilesEnforcesNoteCapAcrossInputs() {
        // R2-F1: the per-file conversion shared by the list path and
        // the ZIP path caps the run at 5,000 notes — the 5,001st input
        // is reported as skipped, never silently dropped.
        val inputs = (0..5_000).map { i ->
            MarkdownFileInput("n$i.md", "n$i.md", "body $i", 1_700_000_000_000L)
        }
        val result = MarkdownImporter.parseMarkdownFiles(inputs, null)
        assertEquals(5_000, result.notes.size)
        assertEquals(
            listOf(
                SkippedImportFile(
                    "n5000.md",
                    "Import limit reached (5,000 notes per run).",
                ),
            ),
            result.skippedFiles,
        )
    }

    @Test
    fun supportedPickTypesMatchWhatTheEngineAccepts() {
        // R2-F2: ZIPs, Markdown/text, and the resolver's image types
        // stage; anything else is rejected before any copy.
        for (name in listOf("takeout.zip", "note.md", "note.markdown", "note.txt", "pic.png", "pic.jpg", "pic.jpeg", "pic.gif", "pic.webp", "pic.avif", "pic.svg", "pic.bmp", "NOTE.MD", "Pic.PNG")) {
            assertTrue(name, PickedFileStaging.isSupportedPick(name))
        }
        for (name in listOf("doc.pdf", "app.exe", "data.json", "notes", "archive.tar.gz", "photo.heic")) {
            assertFalse(name, PickedFileStaging.isSupportedPick(name))
        }
    }

    @Test
    fun cappedCopyKeepsBytesUnderCap() {
        val target = File.createTempFile("stage-ok", ".bin")
        val bytes = ByteArray(100) { it.toByte() }
        assertTrue(PickedFileStaging.copyCapped(ByteArrayInputStream(bytes), target, 100L))
        assertEquals(bytes.toList(), target.readBytes().toList())
        target.delete()
    }

    @Test
    fun cappedCopyDeletesPartialOverCap() {
        // R2-F2: one byte past the cap trips the copy and leaves no
        // partial file behind.
        val target = File.createTempFile("stage-over", ".bin")
        assertFalse(
            PickedFileStaging.copyCapped(ByteArrayInputStream(ByteArray(101)), target, 100L),
        )
        assertFalse(target.exists())
    }

    @Test
    fun cappedCopyAcceptsEmptyStream() {
        val target = File.createTempFile("stage-empty", ".bin")
        assertTrue(PickedFileStaging.copyCapped(ByteArrayInputStream(ByteArray(0)), target, 100L))
        assertEquals(0L, target.length())
        target.delete()
    }

    @Test
    fun aggregateTextBudgetSkipsOverBudgetNotes() {
        // R6-F1: twenty-one 800K-char files total ~16.8M chars —
        // past the 16M aggregate budget. Twenty parse; the last is
        // reported as skipped, never silently dropped. Files stay at
        // 800K chars on purpose: a single note past the ~1 MB
        // per-note bound is refused for its own size (R7-F1), so the
        // aggregate test must use individually legal notes.
        val files = (0 until 21).map { i ->
            MarkdownFileInput(
                "big-$i.md",
                "big-$i.md",
                "# Big$i\n\n" + "x".repeat(800_000),
                1_700_000_000_000L + i,
            )
        }
        val result = MarkdownImporter.parseMarkdownFiles(files, null)
        assertEquals(20, result.notes.size)
        assertEquals((0 until 20).map { "Big$it" }, result.notes.map { it.title })
        assertEquals(
            listOf(SkippedImportFile("big-20.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)),
            result.skippedFiles,
        )
    }

    @Test
    fun noteOverPerNoteCapIsSkipped() {
        // R7-F1: a single note over ~1 MB of text would write fine
        // and then break the run-end rebuild (getAll() cannot read a
        // row past Android's ~2 MiB CursorWindow) — it is refused at
        // parse time with its own reason, while a note just under the
        // cap still imports.
        val big = MarkdownFileInput(
            "big.md", "big.md", "# Big\n\n" + "x".repeat(1_100_000), 1_700_000_000_000L,
        )
        val result = MarkdownImporter.parseMarkdownFile(big, null)
        assertEquals(0, result.notes.size)
        assertEquals(
            listOf(SkippedImportFile("big.md", ImportCaps.NOTE_TOO_LARGE_REASON)),
            result.skippedFiles,
        )
        val ok = MarkdownFileInput(
            "ok.md", "ok.md", "# Ok\n\n" + "x".repeat(900_000), 1_700_000_000_000L,
        )
        val okResult = MarkdownImporter.parseMarkdownFile(ok, null)
        assertEquals(1, okResult.notes.size)
        assertTrue(okResult.skippedFiles.isEmpty())
    }

    @Test
    fun charBoundedReadReportsAllThreeOutcomes() {
        // R7-F3: the char-bounded read stops retaining past the
        // budget, and still tells a >100 MiB entry apart — an entry
        // past BOTH bounds reports the per-file reason, not the
        // budget's.
        val zip = File.createTempFile("char-bounded", ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for ((name, body) in linkedMapOf(
                "small.md" to "x".repeat(100),
                "mid.md" to "y".repeat(200),
                "huge.md" to "z".repeat(200),
            )) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        val reader = ZipImportReader(zip)
        val results = LinkedHashMap<String, CharBoundedText>()
        reader.walk { entry ->
            val (maxChars, maxBytes) = when (entry.name) {
                "small.md" -> 150L to 100L
                "mid.md" -> 150L to 1_000L
                else -> 150L to 100L
            }
            results[entry.name] = entry.readTextUpToChars(maxChars, maxBytes)
        }
        assertEquals(CharBoundedText.Ok("x".repeat(100)), results["small.md"])
        assertEquals(CharBoundedText.OverChars, results["mid.md"])
        assertEquals(CharBoundedText.OverBytes, results["huge.md"])
        zip.delete()
    }

    @Test
    fun stagingBudgetCapsTotalAcrossFiles() {
        // R6-F2: copyCapped bounds each file, but a selection's total
        // needs its own budget — a second file past the remaining
        // total is rejected (nothing staged) while a smaller one
        // still fits.
        val budget = PickedFileStaging.StagingBudget(totalBytes = 100)
        val dir = File.createTempFile("staging-budget", ".dir")
            .apply { delete(); mkdir() }
        try {
            val first = File(dir, "a.bin")
            assertEquals(
                PickedFileStaging.StagedPick.Ok(60),
                PickedFileStaging.stageOne(ByteArrayInputStream(ByteArray(60)), first, budget),
            )
            assertEquals(40L, budget.remaining())
            val second = File(dir, "b.bin")
            assertEquals(
                PickedFileStaging.StagedPick.OverBudget,
                PickedFileStaging.stageOne(ByteArrayInputStream(ByteArray(60)), second, budget),
            )
            assertFalse(second.exists())
            val third = File(dir, "c.bin")
            assertEquals(
                PickedFileStaging.StagedPick.Ok(40),
                PickedFileStaging.stageOne(ByteArrayInputStream(ByteArray(40)), third, budget),
            )
            assertEquals(0L, budget.remaining())
        } finally {
            dir.deleteRecursively()
        }
    }
}
