package app.nota.data.importing

import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
}
