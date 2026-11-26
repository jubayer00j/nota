package app.nota.data.importing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.CRC32
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
    fun unknownTimestampIsAStableZero() {
        // R13-F1: a source with no timestamp used to get the wall
        // clock, so reimporting it produced a different (title,
        // createdAt) key and duplicated the note. The fallback is now
        // a stable 0 — the same source parses to the same stamp every
        // time — while a timestamped source still keeps its real stamp.
        val first = parseOne("note.txt", "body", lastModified = 0L)
        val second = parseOne("note.txt", "body", lastModified = 0L)
        assertEquals(1, first.notes.size)
        assertEquals(0L, first.notes[0].createdAt)
        assertEquals(0L, first.notes[0].updatedAt)
        assertEquals(first.notes[0].createdAt, second.notes[0].createdAt)
        val stamped = parseOne("note.txt", "body", lastModified = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, stamped.notes[0].createdAt)
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
    fun nestedTaskDoesNotMakePlainParentATaskList() {
        // R15-F1: a nested task under a plain parent used to make the
        // parent level a task list, importing the parent as an unchecked
        // task. Task-ness is decided per level, from that level's own
        // items — the nested level stays a task list, the parent stays
        // a plain list item.
        val result = parseOne("nested-task.md", "- parent\n  - [x] child")
        assertEquals(
            "<ul><li><p>parent</p>" +
                "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>child</p></div></li>" +
                "</ul></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun taskParentWithPlainNestedChildStaysATaskList() {
        // R15-F1 mirror: the parent level has its own task item, so it
        // stays a task list even though the nested child is plain.
        val result = parseOne("task-parent.md", "- [ ] parent\n  - child")
        assertEquals(
            "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>parent</p>" +
                "<ul><li><p>child</p></li></ul></div></li></ul>",
            result.notes[0].contentHtml,
        )
    }

    @Test
    fun taskGrandchildDoesNotMakeTopParentATaskList() {
        // R15-F1 one level down: a task grandchild must not make the
        // top level a task list. Deeper indents clamp to the first
        // nested level, so the grandchild renders as a sibling of the
        // middle item — and that nested level, which genuinely
        // contains a task item, is a task list.
        val result = parseOne("grandchild.md", "- parent\n  - middle\n    - [x] grandchild")
        assertEquals(
            "<ul><li><p>parent</p>" +
                "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>middle</p></div></li>" +
                "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>grandchild</p></div></li>" +
                "</ul></li></ul>",
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
    fun aggregateTextBudgetCountsTitles() {
        // R19-F1: addNote used to count only the body toward the
        // 16M-char aggregate budget, so twenty-one notes with ~1 MB
        // titles and tiny bodies all fit while retaining ~19M chars
        // of titles. Titles count now: eighteen parse (18 × 900,007
        // ≈ 16.2M chars) and the last three are reported as skipped,
        // never silently dropped. Titles stay at 900K chars on
        // purpose: a single note past the ~1 MB per-note bound is
        // refused for its own size (R7-F1), so the aggregate test
        // must use individually legal notes.
        val result = ImportParseResult()
        repeat(21) { i ->
            val added = result.addNote(
                ImportedNote(
                    title = "T".repeat(900_000), contentHtml = "<p>x</p>",
                    createdAt = 1_700_000_000_000L + i, updatedAt = 1_700_000_000_000L,
                    pinned = false, archived = false, trashedAt = null,
                    tagNames = emptyList(), folderName = null, images = emptyList(),
                    sourceName = "t-$i.md",
                ),
            )
            assertEquals(i < 18, added)
        }
        assertEquals(18, result.notes.size)
        assertEquals(18L * 900_007L, result.retainedTextChars)
        assertEquals(
            (18 until 21).map {
                SkippedImportFile("t-$it.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)
            },
            result.skippedFiles,
        )
    }

    @Test
    fun enforceContentBudgetSubtractsTitles() {
        // R19-F1: enforceContentBudget used to subtract only the
        // body when dropping a note, so a merged run over budget
        // through titles alone never trimmed — the counter barely
        // moved and every note stayed. Two 10-note sources of 900K
        // titles total ~18.0M chars; the merged result must drop
        // its two tail notes and land at 18 × 900,007 chars.
        val first = ImportParseResult()
        repeat(10) { i ->
            assertTrue(
                first.addNote(
                    ImportedNote(
                        title = "T".repeat(900_000), contentHtml = "<p>x</p>",
                        createdAt = 1_700_000_000_000L + i, updatedAt = 1_700_000_000_000L,
                        pinned = false, archived = false, trashedAt = null,
                        tagNames = emptyList(), folderName = null, images = emptyList(),
                        sourceName = "a-$i.md",
                    ),
                ),
            )
        }
        val second = ImportParseResult()
        repeat(10) { i ->
            assertTrue(
                second.addNote(
                    ImportedNote(
                        title = "T".repeat(900_000), contentHtml = "<p>x</p>",
                        createdAt = 1_800_000_000_000L + i, updatedAt = 1_800_000_000_000L,
                        pinned = false, archived = false, trashedAt = null,
                        tagNames = emptyList(), folderName = null, images = emptyList(),
                        sourceName = "b-$i.md",
                    ),
                ),
            )
        }
        val merged = ImportParseResult()
        merged.absorb(first)
        merged.absorb(second)
        merged.enforceContentBudget()
        assertEquals(18, merged.notes.size)
        assertEquals(18L * 900_007L, merged.retainedTextChars)
        assertEquals(
            listOf(
                SkippedImportFile("b-8.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON),
                SkippedImportFile("b-9.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON),
            ),
            merged.skippedFiles,
        )
    }

    @Test
    fun aggregateTextBudgetCountsTitlesInMarkdownFiles() {
        // R19-F1, parse level: twenty-one files with ~1 MB
        // front-matter titles and one-character bodies. Eighteen
        // parse; the last three are reported as skipped with the
        // budget reason — the precheck counts the source text and
        // addNote counts the converted title, so the run trips the
        // budget on titles alone. Front-matter titles are used on
        // purpose: the per-note estimator counts a front-matter
        // title exactly once (its lines are not body text), while
        // an H1 title is double-counted there (title bound + body
        // bytes) and would be refused by the per-note bound first
        // — see the fix-22 notes.
        val files = (0 until 21).map { i ->
            MarkdownFileInput(
                "t-$i.md",
                "t-$i.md",
                "---\ntitle: " + "T".repeat(900_000) + "\n---\n\nx",
                1_700_000_000_000L + i,
            )
        }
        val result = MarkdownImporter.parseMarkdownFiles(files, null)
        assertEquals(18, result.notes.size)
        assertTrue(result.notes.all { it.title == "T".repeat(900_000) })
        assertEquals(
            (18 until 21).map {
                SkippedImportFile("t-$it.md", ImportCaps.AGGREGATE_TEXT_BUDGET_REASON)
            },
            result.skippedFiles,
        )
    }

    @Test
    fun estimateConvertedNoteBytesCountsTitle() {
        // R19-F1, precheck level: the per-note estimator already
        // bounds the title (front-matter title, leading H1, or file
        // name — whichever the converter would use), so a note whose
        // TITLE alone passes the ~1 MB per-note bound is refused
        // before conversion, while a 900K-char title still imports.
        // Front-matter titles are the exact-count case (their lines
        // are not body text); H1 titles are double-counted by the
        // estimator (title bound + body bytes), so the H1 assertions
        // below only pin the direction, not the exact value — see
        // the fix-22 notes.
        val hugeH1 = MarkdownFileInput(
            "huge.md", "huge.md", "# " + "T".repeat(1_000_000) + "\n\nx",
            1_700_000_000_000L,
        )
        assertTrue(
            MarkdownImporter.estimateConvertedNoteBytes(hugeH1) >
                ImportCaps.MAX_NOTE_CONTENT_BYTES,
        )
        val h1UnderBound = MarkdownFileInput(
            "h1.md", "h1.md", "# " + "T".repeat(400_000) + "\n\nx",
            1_700_000_000_000L,
        )
        assertTrue(
            MarkdownImporter.estimateConvertedNoteBytes(h1UnderBound) <=
                ImportCaps.MAX_NOTE_CONTENT_BYTES,
        )
        val frontMatterTitle = MarkdownFileInput(
            "fm.md", "fm.md",
            "---\ntitle: " + "T".repeat(900_000) + "\n---\n\nx",
            1_700_000_000_000L,
        )
        val fmEstimate = MarkdownImporter.estimateConvertedNoteBytes(frontMatterTitle)
        assertTrue(fmEstimate > 900_000L)
        assertTrue(fmEstimate <= ImportCaps.MAX_NOTE_CONTENT_BYTES)
        val txtTitle = MarkdownFileInput(
            "T".repeat(900_000) + ".txt", "T".repeat(900_000) + ".txt", "x",
            1_700_000_000_000L,
        )
        assertTrue(
            MarkdownImporter.estimateConvertedNoteBytes(txtTitle) > 900_000L,
        )
        val hugeTxtTitle = MarkdownFileInput(
            "T".repeat(1_000_000) + ".txt", "T".repeat(1_000_000) + ".txt", "x",
            1_700_000_000_000L,
        )
        assertTrue(
            MarkdownImporter.estimateConvertedNoteBytes(hugeTxtTitle) >
                ImportCaps.MAX_NOTE_CONTENT_BYTES,
        )
        // End to end: a 1M-char front-matter title is refused with
        // the per-note reason before conversion, not retained and
        // rejected later.
        val hugeFm = MarkdownFileInput(
            "huge-fm.md", "huge-fm.md",
            "---\ntitle: " + "T".repeat(1_000_000) + "\n---\n\nx",
            1_700_000_000_000L,
        )
        val refused = MarkdownImporter.parseMarkdownFile(hugeFm, null)
        assertEquals(0, refused.notes.size)
        assertEquals(
            listOf(SkippedImportFile("huge-fm.md", ImportCaps.NOTE_TOO_LARGE_REASON)),
            refused.skippedFiles,
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
    fun fileReadTextUpToCharsBoundsBothDimensions() {
        // R9-F1 seam: the loose-file read stops retaining past the
        // char bound, and the byte bound wins when both trip.
        val small = File.createTempFile("bounded-small", ".txt").apply { writeText("hello") }
        val big = File.createTempFile("bounded-big", ".txt").apply { writeText("x".repeat(100)) }
        try {
            assertEquals(CharBoundedText.Ok("hello"), small.readTextUpToChars(10, 100))
            assertEquals(CharBoundedText.OverChars, big.readTextUpToChars(10, 100))
            assertEquals(CharBoundedText.OverBytes, big.readTextUpToChars(200, 10))
            assertEquals(CharBoundedText.OverBytes, big.readTextUpToChars(10, 10))
            // Multibyte: 10 CJK chars are 30 UTF-8 bytes.
            val cjk = File.createTempFile("bounded-cjk", ".txt").apply { writeText("あ".repeat(10)) }
            try {
                assertEquals(CharBoundedText.OverChars, cjk.readTextUpToChars(5, 100))
                assertEquals(CharBoundedText.OverBytes, cjk.readTextUpToChars(100, 20))
                assertEquals(CharBoundedText.Ok("あ".repeat(10)), cjk.readTextUpToChars(10, 30))
            } finally {
                cjk.delete()
            }
        } finally {
            small.delete()
            big.delete()
        }
    }

    @Test
    fun ampHeavyTextFileIsSkippedBeforeConversion() {
        // R10-F1: two million '&' chars convert to ~10 MB of HTML
        // (& → &amp;) — past the 1 MB per-note bound — so the file
        // is skipped with the per-note reason BEFORE conversion, not
        // after building the expanded string. An ordinary file with
        // a few escapable characters still imports.
        val heavy = parseOne("amps.txt", "&".repeat(2_000_000))
        assertEquals(0, heavy.notes.size)
        assertEquals(
            listOf(SkippedImportFile("amps.txt", ImportCaps.NOTE_TOO_LARGE_REASON)),
            heavy.skippedFiles,
        )
        val light = parseOne("amps.txt", "Fish & chips <delicious> \"yes\"")
        assertEquals(1, light.notes.size)
        assertEquals(
            "<p>Fish &amp; chips &lt;delicious&gt; &quot;yes&quot;</p>",
            light.notes[0].contentHtml,
        )
    }

    @Test
    fun ampHeavyMarkdownFileIsSkippedBeforeConversion() {
        // R10-F1, .md shape: the same expansion inside a Markdown
        // file — the heading is the title, the amp body would expand
        // past the per-note bound.
        val heavy = parseOne("amps.md", "# Amps\n\n" + "&".repeat(2_000_000))
        assertEquals(0, heavy.notes.size)
        assertEquals(
            listOf(SkippedImportFile("amps.md", ImportCaps.NOTE_TOO_LARGE_REASON)),
            heavy.skippedFiles,
        )
    }

    @Test
    fun estimateNeverUnderstatesConvertedNote() {
        // R10-F1: the estimator must be a true upper bound on the
        // note the converter actually builds — checked here against
        // the real converter on adversarial inputs (escaping,
        // emphasis, code, links, images, every block shape, task
        // levels, nesting, front matter, CRLF, lone markers). Each
        // input converts small, so a correct estimator also lets it
        // through: asserting the note lands pins tightness as well
        // as soundness.
        val inputs = listOf(
            "plain.txt" to "Fish & chips <delicious> \"yes\"\nsecond & line",
            "quote.txt" to "\"quoted\" & 'apos' <tag>",
            "all-amps.md" to "# T\n\n" + "&".repeat(500),
            "quotes.md" to "# T\n\n" + "\"".repeat(500),
            "angles.md" to "# T\n\n" + "<>".repeat(500),
            "emphasis.md" to "a **bold &** *it* `co&de` ~~strike~~ __strong__ _em_ end",
            "emphasis-soup.md" to "**a** **b** *c* `d` `e` ~~f~~ [g](https://example.com) [h](mailto:a@b.c)",
            "markers.md" to "* _ ` ~ [ ] ( ) ! * ** *** ````",
            "headings.md" to "# H1 & \n\n## H2 <x>\n\n###### H6\n\n####### not a heading",
            "hr.md" to "a\n\n---\n\n***\n\n___\n\n- - - not a rule",
            "lists.md" to "- one & <two>\n- [ ] task <three>\n- [x] done\n\n1. first\n2. second\n\na. alpha\nb. beta\n\ni. roman\nii. roman2",
            "nested.md" to "- parent\n  - child & <x>\n  - [ ] nested task\n- plain\n\n1. num\n   - bullet child\n   1. num child",
            "mixed.md" to "- bullet\n1. number\n- bullet again\n\na. lettered\n2. number2",
            "quote.md" to "> quoted & <line>\n> second line\n\n> new block",
            "fence.md" to "```kotlin\nval x = a & b < c>\n```\n\nafter & text",
            "front.md" to "---\ntitle: A & B \"quoted\"\ntags: [one, two]\n---\n\nBody & <text> with **bold**.",
            "crlf.md" to "# T\r\n\r\nLine & one\r\nLine <two>\r\n\r\nEnd.",
            "single-char-lines.md" to ("x\n".repeat(200)),
            "all-backticks.md" to "`".repeat(100),
            "long-line.md" to "word ".repeat(2000),
        )
        for ((name, text) in inputs) {
            val input = MarkdownFileInput(name, name, text, 1_700_000_000_000L)
            val estimate = MarkdownImporter.estimateConvertedNoteBytes(input)
            val result = MarkdownImporter.parseMarkdownFiles(listOf(input), null)
            assertEquals(
                "input $name should import (estimator must not false-skip it)",
                1,
                result.notes.size,
            )
            val note = result.notes[0]
            val actual = noteRowBytes(note.title, note.contentHtml)
            assertTrue(
                "estimate $estimate understates actual $actual for $name",
                estimate >= actual,
            )
        }
    }

    @Test
    fun corruptZipWithPkPrefixIsReportedUnreadable() {
        // R11-F2: a file starting with "PK" but with no valid ZIP
        // structure used to yield zero entries silently — a normal
        // "Imported 0 notes" summary. It is now reported as
        // unreadable, whether the junk follows the prefix or the
        // local-file header itself is truncated.
        val corrupt = File.createTempFile("corrupt-zip", ".zip")
            .apply { writeBytes("PKgarbagegarbage".toByteArray(Charsets.UTF_8)) }
        val truncated = File.createTempFile("truncated-zip", ".zip").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04, 0, 0))
        }
        val dir = File.createTempFile("corrupt-staging", ".dir").apply { delete(); mkdir() }
        try {
            for (file in listOf(corrupt, truncated)) {
                val result = MarkdownImporter.parseImportZip(file, dir)
                assertEquals(0, result.notes.size)
                assertEquals(
                    listOf(
                        SkippedImportFile(
                            "ZIP archive",
                            "That file couldn't be read as a ZIP archive.",
                        ),
                    ),
                    result.skippedFiles,
                )
            }
        } finally {
            corrupt.delete()
            truncated.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun validEmptyZipImportsAsEmptyWithoutError() {
        // R11-F2: a valid empty archive (end-of-central-directory
        // record only) still imports as an empty selection — no
        // unreadable-file reason.
        val empty = File.createTempFile("empty-zip", ".zip")
        ZipOutputStream(empty.outputStream().buffered()).use { }
        val dir = File.createTempFile("empty-staging", ".dir").apply { delete(); mkdir() }
        try {
            val result = MarkdownImporter.parseImportZip(empty, dir)
            assertEquals(0, result.notes.size)
            assertEquals(emptyList<SkippedImportFile>(), result.skippedFiles)
        } finally {
            empty.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateZipTextPathsConvertOnce() {
        // R13-F2: two entries sharing one text path are the same
        // logical file — the first occurrence wins, so the ZIP yields
        // one note, not two.
        val zip = zipWithDuplicateEntries(
            listOf(
                "a.md" to "# First\n\none".toByteArray(Charsets.UTF_8),
                "a.md" to "# Second\n\ntwo".toByteArray(Charsets.UTF_8),
            ),
        )
        val dir = File.createTempFile("dup-text-staging", ".dir").apply { delete(); mkdir() }
        try {
            val result = MarkdownImporter.parseImportZip(zip, dir)
            assertEquals(1, result.notes.size)
            assertEquals("First", result.notes[0].title)
            assertTrue(result.skippedFiles.isEmpty())
        } finally {
            zip.delete()
            dir.deleteRecursively()
        }
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
    @Test
    fun stagingFileNameBoundsLongNamesTo255Bytes() {
        // (Codex R17-F2): an overlong basename used to make
        // File.outputStream() throw (ENAMETOOLONG) and fail the whole
        // import. The staged name is now truncated to 255 UTF-8 bytes
        // with the extension kept.
        val longName = "a".repeat(300) + ".png"
        val staged = stagingFileName(longName, mutableSetOf())
        assertEquals(255, staged.toByteArray(Charsets.UTF_8).size)
        assertTrue(staged.endsWith(".png"))
        assertEquals("a".repeat(251) + ".png", staged)
        // Uniqueness still applies: a second identical name gets a
        // suffix and stays within the bound.
        val used = mutableSetOf(staged)
        val second = stagingFileName(longName, used)
        assertEquals(255, second.toByteArray(Charsets.UTF_8).size)
        assertTrue(second.endsWith("-2.png"))
        assertTrue(second != staged)
        // Multi-byte characters are never split: a stem of 126 'é'
        // (252 bytes) truncates to 125 whole characters (250 bytes)
        // rather than a torn final character.
        val multi = "é".repeat(126) + ".png"
        val stagedMulti = stagingFileName(multi, mutableSetOf())
        assertEquals("é".repeat(125) + ".png", stagedMulti)
        assertEquals(254, stagedMulti.toByteArray(Charsets.UTF_8).size)
        // A short name is untouched, extension and all.
        assertEquals("pic.png", stagingFileName("pic.png", mutableSetOf()))
    }
}

/**
 * Writes a ZIP with STORED entries, allowing the same path twice —
 * `ZipOutputStream` refuses duplicate names outright
 * (`ZipException: duplicate entry`), so a raw writer is the only way
 * to build a malformed archive for the duplicate-path tests (Codex
 * R13-F2). Entry times are a fixed DOS timestamp (2020-06-15
 * 12:00:00); times are not under test here. Shared with
 * [NoteImportRobolectricTest] (same package, same test source set).
 */
internal fun zipWithDuplicateEntries(entries: List<Pair<String, ByteArray>>): File {
    val file = File.createTempFile("dup-entries", ".zip")
    val central = ByteArrayOutputStream()
    var offset = 0
    fun le16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
    fun le32(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }
    // DOS date/time for 2020-06-15 12:00:00.
    val dosTime = 0x6000
    val dosDate = 0x50CF
    file.outputStream().buffered().use { out ->
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            val headerOffset = offset
            le32(out, 0x04034b50L)
            le16(out, 20) // version needed
            le16(out, 0) // flags
            le16(out, 0) // method: STORED
            le16(out, dosTime)
            le16(out, dosDate)
            le32(out, crc)
            le32(out, data.size.toLong())
            le32(out, data.size.toLong())
            le16(out, nameBytes.size)
            le16(out, 0) // extra length
            out.write(nameBytes)
            out.write(data)
            offset += 30 + nameBytes.size + data.size
            le32(central, 0x02014b50L)
            le16(central, 20) // version made by
            le16(central, 20) // version needed
            le16(central, 0)
            le16(central, 0)
            le16(central, dosTime)
            le16(central, dosDate)
            le32(central, crc)
            le32(central, data.size.toLong())
            le32(central, data.size.toLong())
            le16(central, nameBytes.size)
            le16(central, 0)
            le16(central, 0)
            le16(central, 0)
            le16(central, 0)
            le32(central, 0)
            le32(central, headerOffset.toLong())
            central.write(nameBytes)
        }
        val centralOffset = offset
        out.write(central.toByteArray())
        le32(out, 0x06054b50L)
        le16(out, 0)
        le16(out, 0)
        le16(out, entries.size)
        le16(out, entries.size)
        le32(out, central.size().toLong())
        le32(out, centralOffset.toLong())
        le16(out, 0)
    }
    return file
}
