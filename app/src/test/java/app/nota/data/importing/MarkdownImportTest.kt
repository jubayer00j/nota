package app.nota.data.importing

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
        val resolver = createImportImageResolver(mapOf("pic.png" to imageFile))
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
        val resolver = createImportImageResolver(emptyMap())
        val result = parseOne("img2.md", "![missing alt](nope.png)", resolver = resolver)
        assertEquals("<p>missing alt</p>", result.notes[0].contentHtml)
        assertEquals(1, result.imagesUnresolved)
    }

    @Test
    fun resolverFallsBackToUniqueBasename() {
        val imageFile = File.createTempFile("shot", ".png").apply { writeBytes(byteArrayOf(9)) }
        val resolver = createImportImageResolver(mapOf("elsewhere/shot.png" to imageFile))
        val resolved = resolver.resolve("docs/note.md", "shot.png")
        assertTrue(resolved != null)
        assertEquals("shot.png", resolved!!.name)
        imageFile.delete()
    }

    @Test
    fun normaliseImportPathResolvesDots() {
        assertEquals("a/c", normaliseImportPath("a/./b/../c"))
        assertEquals("x/y", normaliseImportPath("/x/y"))
        assertEquals("a/b", normaliseImportPath("a\\b"))
    }
}
